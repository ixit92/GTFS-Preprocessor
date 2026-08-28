package de.ixit.gtfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SoakCycleComparator {
    public static final String VALIDATION_VERSION = "0.7.4";
    public static final String COMPARISON_POLICY = "IDENTICAL_RAW_FEEDS";

    private static final List<String> STABLE_ROW_COUNTS = List.of(
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "transfers",
            "calendar",
            "calendar_dates",
            "feed_agencies",
            "service_calendar_summary",
            "stop_search_tokens",
            "stop_area_display_names",
            "display_name_quality_findings"
    );
    private static final List<String> SERVICE_DAY_VALUES = List.of(
            "services",
            "trip_services",
            "base_calendar_services",
            "exception_services",
            "exception_only_services",
            "overflow_stop_times",
            "maximum_service_day_seconds"
    );
    private static final List<String> DISPLAY_QUALITY_VALUES = List.of(
            "finding_count",
            "prefix_finding_count",
            "municipality_only_finding_count",
            "coverage_gap_count",
            "destructive_action_count"
    );
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private SoakCycleComparator() {
    }

    public static SoakCycleComparisonReport compare(
            Path baselineContractReport,
            Path candidateContractReport,
            Path candidateAuditReport,
            String cycleId,
            int maxHeapLimitMb,
            Map<String, String> sourceFeedHashes
    ) throws IOException {
        return compare(
                baselineContractReport,
                candidateContractReport,
                candidateAuditReport,
                null,
                cycleId,
                maxHeapLimitMb,
                sourceFeedHashes
        );
    }

    public static SoakCycleComparisonReport compare(
            Path baselineContractReport,
            Path candidateContractReport,
            Path candidateAuditReport,
            Path candidatePreprocessLog,
            String cycleId,
            int maxHeapLimitMb,
            Map<String, String> sourceFeedHashes
    ) throws IOException {
        JsonNode baseline = JSON.readTree(baselineContractReport.toFile());
        JsonNode candidate = JSON.readTree(candidateContractReport.toFile());
        JsonNode audit = JSON.readTree(candidateAuditReport.toFile());
        List<SoakCycleComparisonReport.Check> checks = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        checkEquals(checks, failures, "preprocessor_version", "0.7.4", text(candidate, "preprocessor_version"));
        checkEquals(checks, failures, "contract_version", "0.7", text(candidate, "contract_version"));

        boolean appReady = candidate.path("app_ready_sqlite").path("app_ready").asBoolean(false);
        checkEquals(checks, failures, "app_ready", "true", Boolean.toString(appReady));
        int routingWarnCount = candidate.path("routing_compatibility_audit").path("warn").asInt(-1);
        checkEquals(checks, failures, "routing_compatibility_warn_count", "0", Integer.toString(routingWarnCount));
        boolean auditPass = audit.path("pass").asBoolean(false);
        checkEquals(checks, failures, "real_feed_audit_pass", "true", Boolean.toString(auditPass));
        Map<String, Long> rowCountDeltas = compareValues(
                checks,
                failures,
                "row_count_",
                baseline.path("row_counts"),
                candidate.path("row_counts"),
                STABLE_ROW_COUNTS
        );

        JsonNode baselineServiceDay = baseline.path("service_day_model");
        JsonNode candidateServiceDay = candidate.path("service_day_model");
        checkEquals(checks, failures, "service_day_pass", "true", Boolean.toString(candidateServiceDay.path("pass").asBoolean(false)));
        checkEquals(checks, failures, "unresolved_trip_services", "0", Long.toString(value(candidateServiceDay, "unresolved_trip_services")));
        checkEquals(checks, failures, "invalid_iana_timezone_services", "0", Long.toString(value(candidateServiceDay, "invalid_iana_timezone_services")));
        checkEquals(checks, failures, "unknown_timezone_trip_services", "0", Long.toString(value(candidateServiceDay, "unknown_timezone_trip_services")));
        checkEquals(checks, failures, "multiple_timezone_trip_services", "0", Long.toString(value(candidateServiceDay, "multiple_timezone_trip_services")));
        Map<String, Long> serviceDayDeltas = compareValues(
                checks,
                failures,
                "service_day_",
                baselineServiceDay,
                candidateServiceDay,
                SERVICE_DAY_VALUES
        );

        JsonNode baselineDisplay = baseline.path("app_ready_sqlite").path("display_name_quality_baseline");
        JsonNode candidateDisplay = candidate.path("app_ready_sqlite").path("display_name_quality_baseline");
        checkEquals(checks, failures, "display_name_audit_pass", "true", Boolean.toString(
                candidate.path("app_ready_sqlite").path("display_name_audit").path("pass").asBoolean(false)
        ));
        checkEquals(checks, failures, "display_quality_pass", "true", Boolean.toString(candidateDisplay.path("pass").asBoolean(false)));
        Map<String, Long> displayQualityDeltas = compareValues(
                checks,
                failures,
                "display_quality_",
                baselineDisplay,
                candidateDisplay,
                DISPLAY_QUALITY_VALUES
        );

        long maximumReportedHeapMb = maximumValue(
                candidate.path("real_feed_validation").path("memory_snapshots_mb")
        );
        if (candidatePreprocessLog != null) {
            maximumReportedHeapMb = Math.max(maximumReportedHeapMb, maximumLogHeapMb(candidatePreprocessLog));
        }
        boolean heapPass = maximumReportedHeapMb >= 0 && maximumReportedHeapMb <= maxHeapLimitMb;
        checks.add(new SoakCycleComparisonReport.Check(
                "maximum_reported_used_heap_mb",
                heapPass ? "PASS" : "FAIL",
                "<= " + maxHeapLimitMb,
                Long.toString(maximumReportedHeapMb)
        ));
        if (!heapPass) {
            failures.add("maximum_reported_used_heap_mb expected <= "
                    + maxHeapLimitMb
                    + " but was "
                    + maximumReportedHeapMb);
        }

        return new SoakCycleComparisonReport(
                VALIDATION_VERSION,
                Instant.now().toString(),
                cycleId,
                COMPARISON_POLICY,
                baselineContractReport.toAbsolutePath().normalize().toString(),
                candidateContractReport.toAbsolutePath().normalize().toString(),
                candidateAuditReport.toAbsolutePath().normalize().toString(),
                Map.copyOf(sourceFeedHashes),
                Map.copyOf(rowCountDeltas),
                Map.copyOf(serviceDayDeltas),
                Map.copyOf(displayQualityDeltas),
                maxHeapLimitMb,
                maximumReportedHeapMb,
                appReady,
                routingWarnCount,
                auditPass,
                List.copyOf(checks),
                List.copyOf(failures),
                failures.isEmpty()
        );
    }

    public static void write(Path output, SoakCycleComparisonReport report) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        JSON.writeValue(output.toFile(), report);
    }

    private static Map<String, Long> compareValues(
            List<SoakCycleComparisonReport.Check> checks,
            List<String> failures,
            String checkPrefix,
            JsonNode baseline,
            JsonNode candidate,
            List<String> fields
    ) {
        Map<String, Long> deltas = new LinkedHashMap<>();
        for (String field : fields) {
            if (!baseline.has(field) || !candidate.has(field)) {
                deltas.put(field, Long.MIN_VALUE);
                checkEquals(
                        checks,
                        failures,
                        checkPrefix + field,
                        "present",
                        !baseline.has(field) ? "missing in baseline" : "missing in candidate"
                );
                continue;
            }
            long expected = value(baseline, field);
            long actual = value(candidate, field);
            deltas.put(field, actual - expected);
            checkEquals(checks, failures, checkPrefix + field, Long.toString(expected), Long.toString(actual));
        }
        return deltas;
    }

    private static long maximumValue(JsonNode values) {
        if (!values.isObject()) {
            return -1;
        }
        long maximum = -1;
        var fields = values.fields();
        while (fields.hasNext()) {
            maximum = Math.max(maximum, fields.next().getValue().asLong(-1));
        }
        return maximum;
    }

    private static long maximumLogHeapMb(Path log) throws IOException {
        long maximum = -1;
        for (String line : Files.readAllLines(log)) {
            int marker = line.indexOf("memory_used_mb=");
            if (marker < 0) {
                continue;
            }
            int start = marker + "memory_used_mb=".length();
            int end = start;
            while (end < line.length() && Character.isDigit(line.charAt(end))) {
                end++;
            }
            if (end > start) {
                maximum = Math.max(maximum, Long.parseLong(line.substring(start, end)));
            }
        }
        return maximum;
    }

    private static long value(JsonNode node, String field) {
        return node.path(field).asLong(Long.MIN_VALUE);
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static void checkEquals(
            List<SoakCycleComparisonReport.Check> checks,
            List<String> failures,
            String id,
            String expected,
            String actual
    ) {
        boolean pass = expected.equals(actual);
        checks.add(new SoakCycleComparisonReport.Check(id, pass ? "PASS" : "FAIL", expected, actual));
        if (!pass) {
            failures.add(id + " expected " + expected + " but was " + actual);
        }
    }
}
