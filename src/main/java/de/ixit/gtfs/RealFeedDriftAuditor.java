package de.ixit.gtfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RealFeedDriftAuditor {
    public static final String AUDIT_VERSION = "0.7.4";
    public static final String PROMOTION_POLICY = "manual_after_review_only";
    public static final int MAX_USED_HEAP_MB = 2300;

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Map<String, Double> ROW_THRESHOLDS = orderedThresholds(
            "stops", 5.0,
            "stop_areas", 5.0,
            "stop_area_members", 5.0,
            "routes", 10.0,
            "trips", 25.0,
            "stop_times", 25.0,
            "transfers", 30.0,
            "calendar", 50.0,
            "calendar_dates", 50.0,
            "feed_agencies", 10.0,
            "service_calendar_summary", 50.0,
            "stop_search_tokens", 10.0,
            "stop_area_display_names", 5.0,
            "display_name_quality_findings", 30.0
    );
    private static final Map<String, Double> SERVICE_THRESHOLDS = orderedThresholds(
            "services", 50.0,
            "trip_services", 25.0,
            "base_calendar_services", 50.0,
            "exception_services", 50.0,
            "exception_only_services", 100.0,
            "overflow_stop_times", 30.0,
            "maximum_service_day_seconds", 10.0
    );
    private static final Map<String, Double> DISPLAY_THRESHOLDS = orderedThresholds(
            "finding_count", 30.0,
            "prefix_finding_count", 30.0,
            "municipality_only_finding_count", 30.0
    );

    private RealFeedDriftAuditor() {
    }

    public static RealFeedDriftReport audit(
            Path baselineContractReport,
            Path candidateContractReport,
            Path candidateAuditReport,
            Map<String, String> baselineSourceHashes,
            Map<String, String> candidateSourceHashes
    ) throws IOException {
        JsonNode baseline = JSON.readTree(baselineContractReport.toFile());
        JsonNode candidate = JSON.readTree(candidateContractReport.toFile());
        JsonNode audit = JSON.readTree(candidateAuditReport.toFile());
        List<String> failures = new ArrayList<>();
        List<RealFeedDriftReport.MetricDrift> metrics = new ArrayList<>();

        requireText(candidate, "preprocessor_version", "0.7.4", failures);
        requireText(candidate, "contract_version", "0.7", failures);
        requireTrue(candidate.path("app_ready_sqlite"), "app_ready", failures);
        requireTrue(candidate.path("app_ready_sqlite").path("display_name_audit"), "pass", failures);
        requireTrue(candidate.path("app_ready_sqlite").path("display_name_quality_baseline"), "pass", failures);
        requireTrue(candidate.path("service_day_model"), "pass", failures);
        String candidateAuditCompatibility = evaluateCandidateAudit(audit, failures);
        requireZero(candidate.path("routing_compatibility_audit"), "warn", failures);
        requireZero(candidate.path("service_day_model"), "unresolved_trip_services", failures);
        requireZero(candidate.path("service_day_model"), "invalid_iana_timezone_services", failures);
        requireZero(candidate.path("service_day_model"), "unknown_timezone_trip_services", failures);
        requireZero(candidate.path("service_day_model"), "multiple_timezone_trip_services", failures);
        JsonNode candidateDisplay = candidate.path("app_ready_sqlite").path("display_name_quality_baseline");
        requireZero(candidateDisplay, "coverage_gap_count", failures);
        requireZero(candidateDisplay, "destructive_action_count", failures);

        long maximumReportedUsedHeapMb = audit.path("performanceEvidence")
                .path("maximum_reported_used_heap_mb")
                .asLong(-1L);
        if (maximumReportedUsedHeapMb < 0) {
            failures.add("HEAP_HEADROOM: candidate audit does not report maximum used heap");
        } else if (maximumReportedUsedHeapMb > MAX_USED_HEAP_MB) {
            failures.add("HEAP_HEADROOM: maximum reported used heap " + maximumReportedUsedHeapMb
                    + " MB exceeds " + MAX_USED_HEAP_MB + " MB");
        }

        compareDomain("ROW_COUNT", baseline.path("row_counts"), candidate.path("row_counts"), ROW_THRESHOLDS, metrics, failures);
        compareDomain("SERVICE_DAY", baseline.path("service_day_model"), candidate.path("service_day_model"), SERVICE_THRESHOLDS, metrics, failures);
        compareDomain(
                "DISPLAY_QUALITY",
                baseline.path("app_ready_sqlite").path("display_name_quality_baseline"),
                candidateDisplay,
                DISPLAY_THRESHOLDS,
                metrics,
                failures
        );

        Map<String, RealFeedDriftReport.SourceRevision> revisions = sourceRevisions(
                baselineSourceHashes,
                candidateSourceHashes,
                failures
        );
        boolean hasChangedSource = revisions.values().stream().anyMatch(RealFeedDriftReport.SourceRevision::changed);
        if (!hasChangedSource) {
            failures.add("NO_NEW_FEED_REVISION: all candidate source hashes equal the baseline");
        }

        boolean pass = failures.isEmpty();
        return new RealFeedDriftReport(
                AUDIT_VERSION,
                Instant.now().toString(),
                baselineContractReport.toAbsolutePath().normalize().toString(),
                candidateContractReport.toAbsolutePath().normalize().toString(),
                candidateAuditReport.toAbsolutePath().normalize().toString(),
                candidateAuditCompatibility,
                MAX_USED_HEAP_MB,
                maximumReportedUsedHeapMb,
                Map.copyOf(revisions),
                List.copyOf(metrics),
                List.copyOf(failures),
                PROMOTION_POLICY,
                pass ? "ELIGIBLE_FOR_MANUAL_REVIEW" : "BLOCKED",
                pass
        );
    }

    private static String evaluateCandidateAudit(JsonNode audit, List<String> failures) {
        if (audit.path("pass").asBoolean(false)) {
            return "PASS";
        }
        JsonNode auditFailures = audit.path("failures");
        if (!auditFailures.isArray() || auditFailures.isEmpty()) {
            failures.add("candidate audit failed without classified failures");
            return "FAIL";
        }
        boolean onlyExpectedRowCountDrift = true;
        for (JsonNode failure : auditFailures) {
            String message = failure.asText("");
            if (!message.startsWith("row-count regression for ")) {
                failures.add("candidate audit failure: " + message);
                onlyExpectedRowCountDrift = false;
            }
        }
        return onlyExpectedRowCountDrift ? "ROW_COUNT_DRIFT_ONLY" : "FAIL";
    }

    public static void write(Path output, RealFeedDriftReport report) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        JSON.writeValue(output.toFile(), report);
    }

    private static void compareDomain(
            String domain,
            JsonNode baseline,
            JsonNode candidate,
            Map<String, Double> thresholds,
            List<RealFeedDriftReport.MetricDrift> metrics,
            List<String> failures
    ) {
        for (Map.Entry<String, Double> entry : thresholds.entrySet()) {
            String field = entry.getKey();
            double threshold = entry.getValue();
            if (!baseline.has(field) || !candidate.has(field)) {
                failures.add(domain + "." + field + " missing from " + (!baseline.has(field) ? "baseline" : "candidate"));
                continue;
            }
            long baselineValue = baseline.path(field).asLong();
            long candidateValue = candidate.path(field).asLong();
            long delta = candidateValue - baselineValue;
            double percent = Math.abs(delta) * 100.0 / Math.max(1L, Math.abs(baselineValue));
            String classification = delta == 0
                    ? "UNCHANGED"
                    : percent <= threshold ? "EXPECTED_FEED_DRIFT" : "REVIEW_REQUIRED";
            metrics.add(new RealFeedDriftReport.MetricDrift(
                    domain,
                    field,
                    baselineValue,
                    candidateValue,
                    delta,
                    percent,
                    threshold,
                    classification
            ));
            if ("REVIEW_REQUIRED".equals(classification)) {
                failures.add(domain + "." + field + " changed by " + String.format(java.util.Locale.ROOT, "%.2f", percent)
                        + "% (threshold " + threshold + "%)");
            }
        }
    }

    private static Map<String, RealFeedDriftReport.SourceRevision> sourceRevisions(
            Map<String, String> baseline,
            Map<String, String> candidate,
            List<String> failures
    ) {
        Map<String, RealFeedDriftReport.SourceRevision> revisions = new LinkedHashMap<>();
        for (String source : baseline.keySet()) {
            String baselineHash = baseline.get(source);
            String candidateHash = candidate.get(source);
            if (candidateHash == null) {
                failures.add("candidate source hash missing: " + source);
                continue;
            }
            revisions.put(source, new RealFeedDriftReport.SourceRevision(
                    baselineHash,
                    candidateHash,
                    !baselineHash.equals(candidateHash)
            ));
        }
        for (String source : candidate.keySet()) {
            if (!baseline.containsKey(source)) {
                failures.add("baseline source hash missing: " + source);
            }
        }
        return revisions;
    }

    private static void requireText(JsonNode node, String field, String expected, List<String> failures) {
        String actual = node.path(field).asText("");
        if (!expected.equals(actual)) {
            failures.add(field + " expected " + expected + " but was " + actual);
        }
    }

    private static void requireTrue(JsonNode node, String field, List<String> failures) {
        if (!node.path(field).asBoolean(false)) {
            failures.add(field + " expected true");
        }
    }

    private static void requireZero(JsonNode node, String field, List<String> failures) {
        long actual = node.path(field).asLong(Long.MIN_VALUE);
        if (actual != 0) {
            failures.add(field + " expected 0 but was " + actual);
        }
    }

    private static Map<String, Double> orderedThresholds(Object... values) {
        Map<String, Double> thresholds = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            thresholds.put((String) values[index], (Double) values[index + 1]);
        }
        return Collections.unmodifiableMap(thresholds);
    }
}
