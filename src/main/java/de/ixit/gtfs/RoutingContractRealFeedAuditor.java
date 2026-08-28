package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.DisplayNameEvidence;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.LatencySummary;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.OverflowTimeAudit;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.OverflowTimeEvidence;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.ScenarioResult;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.ServiceDayExceptionAudit;
import de.ixit.gtfs.RoutingContractRealFeedAuditReport.ServiceDayExceptionEvidence;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoutingContractRealFeedAuditor {
    public static final String AUDIT_VERSION = "0.8.1";

    private static final int SERVICE_DAY_BOUNDARY_SECONDS = 24 * 60 * 60;
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private RoutingContractRealFeedAuditor() {
    }

    public static RoutingContractRealFeedAuditReport audit(
            Path database,
            Path scenarioFile,
            String inputProvenance,
            Map<String, String> sourceFeeds,
            List<String> requiredCities,
            long maximumQueryMs,
            int exceptionSampleLimit
    ) throws IOException, SQLException {
        Path current = requireFile(database, "database");
        Path scenariosPath = requireFile(scenarioFile, "scenario file");
        validateOptions(inputProvenance, sourceFeeds, requiredCities, maximumQueryMs, exceptionSampleLimit);
        ScenarioManifest manifest = JSON.readValue(scenariosPath.toFile(), ScenarioManifest.class);
        validateManifest(manifest);

        List<String> failures = new ArrayList<>();
        List<ScenarioResult> scenarioResults = new ArrayList<>();
        try (Connection connection = openReadOnly(current)) {
            for (Scenario scenario : manifest.scenarios()) {
                scenarioResults.add(runScenario(current, connection, scenario, maximumQueryMs));
            }
            for (ScenarioResult result : scenarioResults) {
                result.failures().forEach(failure -> failures.add(result.id() + ": " + failure));
            }
            validateRequiredCities(requiredCities, scenarioResults, failures);

            ServiceDayExceptionAudit serviceDayExceptions = auditServiceDayExceptions(
                    connection,
                    exceptionSampleLimit
            );
            if (!serviceDayExceptions.pass()) {
                failures.add("calendar_dates additions/removals were not resolved as contractually required");
            }

            OverflowTimeAudit overflowTimes = auditOverflowTimes(connection, exceptionSampleLimit);
            if (!overflowTimes.pass()) {
                failures.add("no valid stop_times evidence at or above 24:00:00 was found");
            }

            LatencySummary latency = summarizeLatency(scenarioResults, maximumQueryMs);
            if (!latency.pass()) {
                failures.add("at least one consumer query exceeded " + maximumQueryMs + " ms");
            }

            return new RoutingContractRealFeedAuditReport(
                    AUDIT_VERSION,
                    Instant.now().toString(),
                    current.toString(),
                    sha256(current),
                    Files.size(current),
                    inputProvenance,
                    Map.copyOf(sourceFeeds),
                    manifest.scenarioVersion(),
                    List.copyOf(requiredCities),
                    List.copyOf(scenarioResults),
                    serviceDayExceptions,
                    overflowTimes,
                    latency,
                    List.copyOf(failures),
                    failures.isEmpty()
            );
        }
    }

    public static void write(Path output, RoutingContractRealFeedAuditReport report) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        JSON.writeValue(normalized.toFile(), report);
    }

    private static ScenarioResult runScenario(
            Path database,
            Connection connection,
            Scenario scenario,
            long maximumQueryMs
    ) {
        long startedNanos = System.nanoTime();
        List<String> failures = new ArrayList<>();
        RoutingContractConsumerReport consumer = null;
        DisplayNameEvidence startDisplay = missingDisplay(scenario.startAreaId(), scenario.city());
        DisplayNameEvidence targetDisplay = missingDisplay(scenario.targetAreaId(), scenario.city());
        try {
            consumer = RoutingContractConsumerPoc.inspect(
                    database,
                    LocalDate.parse(scenario.serviceDate()),
                    scenario.startAreaId(),
                    scenario.targetAreaId(),
                    parseTime(scenario.fromTime()),
                    parseTime(scenario.toTime()),
                    scenario.limit()
            );
            startDisplay = readDisplayName(connection, scenario.startAreaId(), scenario.city());
            targetDisplay = readDisplayName(connection, scenario.targetAreaId(), scenario.city());
            if (!consumer.pass()) {
                failures.add("consumer validation failed: " + String.join("; ", consumer.failures()));
            }
            if (!startDisplay.pass()) {
                failures.add("start display name does not match 'Haltestelle, Stadtname'");
            }
            if (!targetDisplay.pass()) {
                failures.add("target display name does not match 'Haltestelle, Stadtname'");
            }
        } catch (Exception exception) {
            failures.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        long elapsedMs = elapsedMillis(startedNanos);
        if (elapsedMs > maximumQueryMs) {
            failures.add("query latency " + elapsedMs + " ms exceeds " + maximumQueryMs + " ms");
        }
        return new ScenarioResult(
                scenario.id(),
                scenario.city(),
                elapsedMs,
                startDisplay,
                targetDisplay,
                consumer,
                List.copyOf(failures),
                failures.isEmpty()
        );
    }

    private static DisplayNameEvidence readDisplayName(
            Connection connection,
            String areaId,
            String expectedCity
    ) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT public_stop_name, public_city_name, public_display_name, display_quality
                FROM stop_area_display_names
                WHERE area_id = ?
                """)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return missingDisplay(areaId, expectedCity);
                }
                String stopName = value(resultSet, "public_stop_name");
                String cityName = value(resultSet, "public_city_name");
                String displayName = value(resultSet, "public_display_name");
                String quality = value(resultSet, "display_quality");
                boolean cityMatches = Objects.equals(expectedCity, cityName);
                boolean patternMatches = !stopName.isBlank()
                        && !cityName.isBlank()
                        && Objects.equals(displayName, stopName + ", " + cityName);
                return new DisplayNameEvidence(
                        areaId,
                        stopName,
                        cityName,
                        displayName,
                        quality,
                        cityMatches,
                        patternMatches,
                        cityMatches && patternMatches
                );
            }
        }
    }

    private static DisplayNameEvidence missingDisplay(String areaId, String expectedCity) {
        return new DisplayNameEvidence(areaId, "", "", "", "MISSING", false, false, false);
    }

    private static ServiceDayExceptionAudit auditServiceDayExceptions(
            Connection connection,
            int sampleLimit
    ) throws SQLException {
        long additions = count(connection, "SELECT COUNT(*) FROM calendar_dates WHERE exception_type = 1");
        long removals = count(connection, "SELECT COUNT(*) FROM calendar_dates WHERE exception_type = 2");
        List<ServiceDayExceptionEvidence> samples = new ArrayList<>();
        samples.addAll(readExceptionSamples(connection, 1, sampleLimit));
        samples.addAll(readExceptionSamples(connection, 2, sampleLimit));
        boolean additionsObserved = samples.stream().anyMatch(sample -> sample.exceptionType() == 1 && sample.pass());
        boolean removalsObserved = samples.stream().anyMatch(sample -> sample.exceptionType() == 2 && sample.pass());
        boolean allSamplesPass = samples.stream().allMatch(ServiceDayExceptionEvidence::pass);
        return new ServiceDayExceptionAudit(
                additions,
                removals,
                List.copyOf(samples),
                additionsObserved,
                removalsObserved,
                additions > 0 && removals > 0 && additionsObserved && removalsObserved && allSamplesPass
        );
    }

    private static List<ServiceDayExceptionEvidence> readExceptionSamples(
            Connection connection,
            int exceptionType,
            int limit
    ) throws SQLException {
        List<ServiceDayExceptionEvidence> samples = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT cd.service_id, cd.date, cd.exception_type,
                       (SELECT COUNT(*) FROM trips WHERE service_id = cd.service_id) AS trip_count
                FROM calendar_dates cd
                WHERE cd.exception_type = ?
                ORDER BY cd.date, cd.service_id
                LIMIT ?
                """)) {
            statement.setInt(1, exceptionType);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String serviceId = resultSet.getString("service_id");
                    String date = resultSet.getString("date");
                    LocalDate serviceDate = LocalDate.parse(date, GTFS_DATE);
                    var resolution = ServiceDayResolver.resolve(connection, serviceId, serviceDate);
                    boolean expectedActive = exceptionType == 1;
                    String expectedReason = exceptionType == 1
                            ? "CALENDAR_DATES_ADDITION"
                            : "CALENDAR_DATES_REMOVAL";
                    boolean pass = resolution.active() == expectedActive
                            && expectedReason.equals(resolution.reason());
                    samples.add(new ServiceDayExceptionEvidence(
                            serviceId,
                            serviceDate.toString(),
                            exceptionType,
                            expectedActive,
                            resolution.active(),
                            resolution.reason(),
                            resultSet.getLong("trip_count"),
                            pass
                    ));
                }
            }
        }
        return samples;
    }

    private static OverflowTimeAudit auditOverflowTimes(Connection connection, int sampleLimit) throws SQLException {
        long count = count(connection, """
                SELECT COUNT(*) FROM stop_times
                WHERE arrival_seconds >= 86400 OR departure_seconds >= 86400
                """);
        int maximum = queryInt(connection, """
                SELECT COALESCE(MAX(MAX(arrival_seconds, departure_seconds)), 0) FROM stop_times
                """);
        List<OverflowTimeEvidence> samples = new ArrayList<>();
        try (var statement = connection.prepareStatement("""
                SELECT st.trip_id, tr.route_id, tr.service_id, st.stop_id, st.stop_sequence,
                       st.arrival_seconds, st.departure_seconds
                FROM stop_times st
                LEFT JOIN trips tr ON tr.trip_id = st.trip_id
                WHERE st.arrival_seconds >= 86400 OR st.departure_seconds >= 86400
                ORDER BY MAX(st.arrival_seconds, st.departure_seconds) DESC, st.trip_id, st.stop_sequence
                LIMIT ?
                """)) {
            statement.setInt(1, sampleLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int arrival = resultSet.getInt("arrival_seconds");
                    int departure = resultSet.getInt("departure_seconds");
                    boolean tripReferenceValid = resultSet.getString("route_id") != null
                            && resultSet.getString("service_id") != null;
                    boolean pass = tripReferenceValid
                            && (arrival >= SERVICE_DAY_BOUNDARY_SECONDS
                            || departure >= SERVICE_DAY_BOUNDARY_SECONDS);
                    samples.add(new OverflowTimeEvidence(
                            resultSet.getString("trip_id"),
                            value(resultSet, "route_id"),
                            value(resultSet, "service_id"),
                            resultSet.getString("stop_id"),
                            resultSet.getInt("stop_sequence"),
                            arrival,
                            departure,
                            formatSeconds(arrival),
                            formatSeconds(departure),
                            tripReferenceValid,
                            pass
                    ));
                }
            }
        }
        boolean pass = count > 0
                && maximum >= SERVICE_DAY_BOUNDARY_SECONDS
                && !samples.isEmpty()
                && samples.stream().allMatch(OverflowTimeEvidence::pass);
        return new OverflowTimeAudit(count, maximum, formatSeconds(maximum), List.copyOf(samples), pass);
    }

    private static LatencySummary summarizeLatency(List<ScenarioResult> scenarios, long maximumQueryMs) {
        List<Long> elapsed = scenarios.stream()
                .map(ScenarioResult::elapsedMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (elapsed.isEmpty()) {
            return new LatencySummary(maximumQueryMs, 0, 0, 0, 0, 0, false);
        }
        long total = elapsed.stream().mapToLong(Long::longValue).sum();
        int p95Index = Math.max(0, (int) Math.ceil(elapsed.size() * 0.95) - 1);
        long maximum = elapsed.get(elapsed.size() - 1);
        return new LatencySummary(
                maximumQueryMs,
                elapsed.get(0),
                total / elapsed.size(),
                elapsed.get(p95Index),
                maximum,
                elapsed.size(),
                maximum <= maximumQueryMs
        );
    }

    private static void validateRequiredCities(
            List<String> requiredCities,
            List<ScenarioResult> scenarios,
            List<String> failures
    ) {
        Set<String> passingCities = new LinkedHashSet<>();
        scenarios.stream().filter(ScenarioResult::pass).map(ScenarioResult::city).forEach(passingCities::add);
        for (String city : requiredCities) {
            if (!passingCities.contains(city)) {
                failures.add("required city has no passing scenario: " + city);
            }
        }
    }

    private static void validateOptions(
            String inputProvenance,
            Map<String, String> sourceFeeds,
            List<String> requiredCities,
            long maximumQueryMs,
            int exceptionSampleLimit
    ) {
        if (inputProvenance == null || inputProvenance.isBlank()) {
            throw new IllegalArgumentException("input provenance is required");
        }
        if (sourceFeeds == null || sourceFeeds.isEmpty()) {
            throw new IllegalArgumentException("at least one source feed hash is required");
        }
        sourceFeeds.forEach((name, hash) -> {
            if (name == null || name.isBlank() || hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("source feeds require NAME=SHA256 values");
            }
        });
        if (requiredCities == null || requiredCities.isEmpty()) {
            throw new IllegalArgumentException("at least one required city is required");
        }
        if (maximumQueryMs < 1 || exceptionSampleLimit < 1 || exceptionSampleLimit > 100) {
            throw new IllegalArgumentException("invalid latency or exception sample limit");
        }
    }

    private static void validateManifest(ScenarioManifest manifest) {
        if (manifest == null || !AUDIT_VERSION.equals(manifest.scenarioVersion())) {
            throw new IllegalArgumentException("scenario_version must be " + AUDIT_VERSION);
        }
        if (manifest.scenarios() == null || manifest.scenarios().isEmpty()) {
            throw new IllegalArgumentException("at least one routing scenario is required");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Scenario scenario : manifest.scenarios()) {
            if (scenario == null || isBlank(scenario.id()) || isBlank(scenario.city())
                    || isBlank(scenario.serviceDate()) || isBlank(scenario.startAreaId())
                    || isBlank(scenario.targetAreaId()) || isBlank(scenario.fromTime())
                    || isBlank(scenario.toTime())) {
                throw new IllegalArgumentException("every routing scenario requires all fields");
            }
            if (!ids.add(scenario.id())) {
                throw new IllegalArgumentException("duplicate scenario id: " + scenario.id());
            }
            LocalDate.parse(scenario.serviceDate());
            int from = parseTime(scenario.fromTime());
            int to = parseTime(scenario.toTime());
            if (to < from || scenario.limit() < 1 || scenario.limit() > 100) {
                throw new IllegalArgumentException("invalid window or limit for scenario " + scenario.id());
            }
        }
    }

    private static Connection openReadOnly(Path path) throws SQLException {
        return DriverManager.getConnection(
                "jdbc:sqlite:" + path.toAbsolutePath().normalize().toUri() + "?mode=ro&immutable=1"
        );
    }

    private static Path requireFile(Path path, String label) {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " does not exist: " + path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static String value(ResultSet resultSet, String column) throws SQLException {
        String value = resultSet.getString(column);
        return value == null ? "" : value;
    }

    private static int parseTime(String value) {
        return GtfsTimeParser.toSecondsSinceServiceDayStart(
                value.chars().filter(character -> character == ':').count() == 1 ? value + ":00" : value
        );
    }

    private static String formatSeconds(int seconds) {
        int hours = seconds / 3_600;
        int minutes = seconds % 3_600 / 60;
        int remainder = seconds % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, remainder);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000L);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read > 0) {
                        digest.update(buffer, 0, read);
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record ScenarioManifest(String scenarioVersion, List<Scenario> scenarios) {
    }

    public record Scenario(
            String id,
            String city,
            String serviceDate,
            String startAreaId,
            String targetAreaId,
            String fromTime,
            String toTime,
            int limit
    ) {
    }
}
