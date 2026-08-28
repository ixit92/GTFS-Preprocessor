package de.ixit.gtfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceDayRealFeedAuditor {
    public static final String AUDIT_VERSION = SqliteContract.PREPROCESSOR_VERSION;

    private static final List<String> ROW_COUNT_TABLES = List.of(
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "transfers",
            "calendar",
            "calendar_dates",
            "stop_search_tokens",
            "stop_area_display_names",
            "feed_agencies",
            "service_calendar_summary"
    );
    private static final List<String> STABLE_BASELINE_TABLES = List.of(
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "transfers",
            "calendar",
            "calendar_dates",
            "stop_search_tokens",
            "stop_area_display_names"
    );

    private ServiceDayRealFeedAuditor() {
    }

    public static ServiceDayRealFeedAuditReport audit(
            Path database,
            Path baselineDatabase,
            Path preprocessReport,
            String inputProvenance,
            Map<String, String> sourceFeeds,
            List<LocalDate> spotcheckDates
    ) throws IOException, SQLException {
        Path current = requireFile(database, "database");
        Path baseline = requireFile(baselineDatabase, "baseline database");
        Path report = requireFile(preprocessReport, "preprocess report");

        Map<String, String> metadata;
        Map<String, Long> rowCounts;
        ServiceDayModelReport serviceDayModel;
        ServiceDayRealFeedAuditReport.ExceptionStatistics exceptionStatistics;
        List<ServiceDayRealFeedAuditReport.ServiceDateSpotcheck> spotchecks = new ArrayList<>();
        try (Connection connection = openReadOnly(current)) {
            metadata = readMetadata(connection);
            rowCounts = readRowCounts(connection);
            serviceDayModel = ServiceDayModelAuditor.audit(connection);
            exceptionStatistics = readExceptionStatistics(connection);
            for (LocalDate date : spotcheckDates) {
                spotchecks.add(readSpotcheck(connection, date));
            }
        }

        Map<String, String> baselineMetadata;
        Map<String, Long> baselineRowCounts;
        try (Connection connection = openReadOnly(baseline)) {
            baselineMetadata = readMetadata(connection);
            baselineRowCounts = readRowCounts(connection);
        }

        Map<String, Long> rowCountDeltas = new LinkedHashMap<>();
        for (String table : ROW_COUNT_TABLES) {
            rowCountDeltas.put(
                    table,
                    rowCounts.getOrDefault(table, 0L) - baselineRowCounts.getOrDefault(table, 0L)
            );
        }

        long currentSize = Files.size(current);
        long baselineSize = Files.size(baseline);
        Map<String, Long> performanceEvidence = readPerformanceEvidence(report);
        List<String> failures = validate(
                metadata,
                baselineMetadata,
                serviceDayModel,
                inputProvenance,
                sourceFeeds,
                spotchecks,
                rowCountDeltas
        );

        return new ServiceDayRealFeedAuditReport(
                AUDIT_VERSION,
                Instant.now().toString(),
                current.toString(),
                sha256(current),
                currentSize,
                inputProvenance,
                Map.copyOf(sourceFeeds),
                Map.copyOf(metadata),
                serviceDayModel,
                exceptionStatistics,
                Map.copyOf(rowCounts),
                new ServiceDayRealFeedAuditReport.BaselineComparison(
                        baseline.toString(),
                        sha256(baseline),
                        baselineSize,
                        currentSize - baselineSize,
                        Map.copyOf(baselineMetadata),
                        Map.copyOf(baselineRowCounts),
                        Map.copyOf(rowCountDeltas)
                ),
                List.copyOf(spotchecks),
                Map.copyOf(performanceEvidence),
                List.copyOf(failures),
                failures.isEmpty()
        );
    }

    private static List<String> validate(
            Map<String, String> metadata,
            Map<String, String> baselineMetadata,
            ServiceDayModelReport serviceDayModel,
            String inputProvenance,
            Map<String, String> sourceFeeds,
            List<ServiceDayRealFeedAuditReport.ServiceDateSpotcheck> spotchecks,
            Map<String, Long> rowCountDeltas
    ) {
        List<String> failures = new ArrayList<>();
        if (!SqliteContract.CONTRACT_VERSION.equals(metadata.get("contract_version"))) {
            failures.add("current contract_version must be " + SqliteContract.CONTRACT_VERSION);
        }
        if (!SqliteContract.PREPROCESSOR_VERSION.equals(metadata.get("preprocessor_version"))) {
            failures.add("current preprocessor_version must be " + SqliteContract.PREPROCESSOR_VERSION);
        }
        if (!"0.6".equals(baselineMetadata.get("contract_version"))) {
            failures.add("baseline contract_version must be 0.6");
        }
        if (!serviceDayModel.pass()) {
            failures.add("service-day model validation failed");
        }
        if (inputProvenance == null || inputProvenance.isBlank()) {
            failures.add("input provenance is required");
        }
        if (sourceFeeds.isEmpty()) {
            failures.add("at least one source feed hash is required");
        }
        for (Map.Entry<String, String> source : sourceFeeds.entrySet()) {
            if (!source.getValue().matches("[0-9a-fA-F]{64}")) {
                failures.add("source feed " + source.getKey() + " has no SHA-256 hash");
            }
        }
        if (spotchecks.isEmpty()) {
            failures.add("at least one service-date spotcheck is required");
        }
        for (String table : STABLE_BASELINE_TABLES) {
            long delta = rowCountDeltas.getOrDefault(table, 0L);
            if (delta != 0) {
                failures.add("row-count regression for " + table + ": delta=" + delta);
            }
        }
        return failures;
    }

    private static ServiceDayRealFeedAuditReport.ExceptionStatistics readExceptionStatistics(
            Connection connection
    ) throws SQLException {
        long additions = count(connection, "SELECT COUNT(*) FROM calendar_dates WHERE exception_type = 1");
        long removals = count(connection, "SELECT COUNT(*) FROM calendar_dates WHERE exception_type = 2");
        long servicesWithAdditions = count(
                connection,
                "SELECT COUNT(DISTINCT service_id) FROM calendar_dates WHERE exception_type = 1"
        );
        long servicesWithRemovals = count(
                connection,
                "SELECT COUNT(DISTINCT service_id) FROM calendar_dates WHERE exception_type = 2"
        );
        long exceptionOnly = count(
                connection,
                "SELECT COUNT(*) FROM service_calendar_summary WHERE status = 'EXCEPTIONS_ONLY'"
        );
        return new ServiceDayRealFeedAuditReport.ExceptionStatistics(
                additions,
                removals,
                servicesWithAdditions,
                servicesWithRemovals,
                exceptionOnly
        );
    }

    private static ServiceDayRealFeedAuditReport.ServiceDateSpotcheck readSpotcheck(
            Connection connection,
            LocalDate date
    ) throws SQLException {
        int weekdayBit = 1 << (date.getDayOfWeek().getValue() - 1);
        String gtfsDate = date.toString().replace("-", "");
        try (var statement = connection.prepareStatement("""
                WITH exact_exception AS (
                    SELECT service_id, exception_type
                    FROM calendar_dates
                    WHERE date = ?
                ), resolved AS (
                    SELECT summary.trip_count,
                           exception.exception_type,
                           CASE WHEN summary.has_calendar = 1
                                      AND ? BETWEEN summary.start_date AND summary.end_date
                                      AND (summary.weekday_mask & ?) <> 0
                                THEN 1 ELSE 0 END AS base_active,
                           CASE WHEN exception.exception_type = 1 THEN 1
                                WHEN exception.exception_type = 2 THEN 0
                                WHEN summary.has_calendar = 1
                                      AND ? BETWEEN summary.start_date AND summary.end_date
                                      AND (summary.weekday_mask & ?) <> 0
                                THEN 1 ELSE 0 END AS active
                    FROM service_calendar_summary summary
                    LEFT JOIN exact_exception exception ON exception.service_id = summary.service_id
                )
                SELECT COALESCE(SUM(active), 0),
                       COALESCE(SUM(CASE WHEN active = 1 THEN trip_count ELSE 0 END), 0),
                       COALESCE(SUM(base_active), 0),
                       COALESCE(SUM(CASE WHEN exception_type = 1 THEN 1 ELSE 0 END), 0),
                       COALESCE(SUM(CASE WHEN exception_type = 2 THEN 1 ELSE 0 END), 0)
                FROM resolved
                """)) {
            statement.setString(1, gtfsDate);
            statement.setString(2, gtfsDate);
            statement.setInt(3, weekdayBit);
            statement.setString(4, gtfsDate);
            statement.setInt(5, weekdayBit);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return new ServiceDayRealFeedAuditReport.ServiceDateSpotcheck(
                        date.toString(),
                        date.getDayOfWeek().name(),
                        resultSet.getLong(1),
                        resultSet.getLong(2),
                        resultSet.getLong(3),
                        resultSet.getLong(4),
                        resultSet.getLong(5)
                );
            }
        }
    }

    private static Map<String, Long> readPerformanceEvidence(Path preprocessReport) throws IOException {
        JsonNode root = new ObjectMapper().readTree(preprocessReport.toFile());
        Map<String, Long> values = new LinkedHashMap<>();
        putLong(values, "total_runtime_ms", root.at("/real_feed_validation/total_runtime_ms"));
        putLong(values, "stop_times_rows", root.at("/sqlite_diagnostics/stop_times_rows"));
        putLong(values, "stop_times_write_ms", root.at("/sqlite_diagnostics/stop_times_write_ms"));
        putLong(values, "stop_times_rows_per_second", root.at("/sqlite_diagnostics/stop_times_rows_per_second"));
        putLong(values, "stop_times_max_commit_ms", root.at("/sqlite_diagnostics/stop_times_max_commit_ms"));
        JsonNode snapshots = root.at("/real_feed_validation/memory_snapshots_mb");
        long maximumMemoryMb = 0;
        if (snapshots.isObject()) {
            var fields = snapshots.fields();
            while (fields.hasNext()) {
                maximumMemoryMb = Math.max(maximumMemoryMb, fields.next().getValue().asLong());
            }
        }
        values.put("maximum_reported_used_heap_mb", maximumMemoryMb);
        return values;
    }

    private static void putLong(Map<String, Long> values, String key, JsonNode value) {
        values.put(key, value.isNumber() ? value.asLong() : 0L);
    }

    private static Map<String, String> readMetadata(Connection connection) throws SQLException {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (!hasTable(connection, "ixit_metadata")) {
            return metadata;
        }
        try (var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT key, value FROM ixit_metadata ORDER BY key")) {
            while (resultSet.next()) {
                metadata.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        return metadata;
    }

    private static Map<String, Long> readRowCounts(Connection connection) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : ROW_COUNT_TABLES) {
            counts.put(table, hasTable(connection, table) ? count(connection, "SELECT COUNT(*) FROM " + table) : 0L);
        }
        return counts;
    }

    private static boolean hasTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
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
}
