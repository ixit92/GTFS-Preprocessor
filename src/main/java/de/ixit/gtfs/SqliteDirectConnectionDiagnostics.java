package de.ixit.gtfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SqliteDirectConnectionDiagnostics {
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int DEFAULT_LIMIT = 10;

    private SqliteDirectConnectionDiagnostics() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("SQLite direct connection diagnostics failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (!Files.isRegularFile(options.database())) {
            throw new IllegalArgumentException("SQLite database not found: " + options.database().toAbsolutePath());
        }

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + options.database().toAbsolutePath())) {
            configureReadOnlyConnection(connection);
            validateContract(connection);

            long startedNanos = System.nanoTime();
            DirectConnectionCounts counts = countDirectConnections(connection, options);
            List<DirectConnectionHit> hits = findDirectConnections(connection, options);
            long elapsedMs = elapsedMillis(startedNanos);

            printReport(connection, options, counts, hits, elapsedMs);
        }
    }

    private static void configureReadOnlyConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    private static void validateContract(Connection connection) throws SQLException {
        List<String> requiredTables = List.of(
                "ixit_metadata",
                "stop_areas",
                "stop_area_members",
                "stops",
                "routes",
                "trips",
                "stop_times",
                "calendar",
                "calendar_dates"
        );
        for (String table : requiredTables) {
            if (!tableExists(connection, table)) {
                throw new IllegalArgumentException("Required SQLite table missing: " + table);
            }
        }

        String contractVersion = metadataValue(connection, "contract_version");
        if (!SqliteContract.SUPPORTED_CONTRACT_VERSIONS.contains(contractVersion)) {
            throw new IllegalArgumentException("Unsupported contract_version: " + contractVersion
                    + " supported " + SqliteContract.SUPPORTED_CONTRACT_VERSIONS);
        }
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        String sql = """
                SELECT 1
                FROM sqlite_master
                WHERE type = 'table' AND name = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String metadataValue(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM ixit_metadata WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("value") : null;
            }
        }
    }

    private static DirectConnectionCounts countDirectConnections(Connection connection, Options options) throws SQLException {
        String sql = directConnectionCte(options, """
                SELECT
                    COUNT(*) AS raw_count,
                    SUM(CASE WHEN active_services.service_id IS NOT NULL THEN 1 ELSE 0 END) AS active_count,
                    SUM(CASE WHEN active_services.active_reason = 'calendar_dates_addition' THEN 1 ELSE 0 END) AS calendar_dates_added_count,
                    SUM(CASE WHEN service_removals.service_id IS NOT NULL THEN 1 ELSE 0 END) AS calendar_dates_removed_count
                FROM direct_candidates
                LEFT JOIN active_services ON active_services.service_id = direct_candidates.service_id
                LEFT JOIN service_removals ON service_removals.service_id = direct_candidates.service_id
                """);

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindCommonParameters(statement, options);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new DirectConnectionCounts(0, 0, 0, 0);
                }
                return new DirectConnectionCounts(
                        resultSet.getLong("raw_count"),
                        resultSet.getLong("active_count"),
                        resultSet.getLong("calendar_dates_added_count"),
                        resultSet.getLong("calendar_dates_removed_count")
                );
            }
        }
    }

    private static List<DirectConnectionHit> findDirectConnections(Connection connection, Options options) throws SQLException {
        String sql = directConnectionCte(options, """
                SELECT
                    direct_candidates.trip_id,
                    direct_candidates.route_id,
                    direct_candidates.route_short_name,
                    direct_candidates.route_long_name,
                    direct_candidates.service_id,
                    direct_candidates.start_stop_id,
                    direct_candidates.start_stop_name,
                    direct_candidates.target_stop_id,
                    direct_candidates.target_stop_name,
                    direct_candidates.start_departure_seconds,
                    direct_candidates.target_arrival_seconds,
                    direct_candidates.start_sequence,
                    direct_candidates.target_sequence,
                    active_services.active_reason
                FROM direct_candidates
                JOIN active_services ON active_services.service_id = direct_candidates.service_id
                ORDER BY direct_candidates.start_departure_seconds,
                         direct_candidates.target_arrival_seconds,
                         direct_candidates.trip_id,
                         direct_candidates.start_sequence,
                         direct_candidates.target_sequence
                LIMIT ?
                """);

        List<DirectConnectionHit> hits = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int nextIndex = bindCommonParameters(statement, options);
            statement.setInt(nextIndex, options.limit());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int departureSeconds = resultSet.getInt("start_departure_seconds");
                    int arrivalSeconds = resultSet.getInt("target_arrival_seconds");
                    hits.add(new DirectConnectionHit(
                            resultSet.getString("trip_id"),
                            resultSet.getString("route_id"),
                            resultSetString(resultSet, "route_short_name"),
                            resultSetString(resultSet, "route_long_name"),
                            resultSet.getString("service_id"),
                            resultSet.getString("start_stop_id"),
                            resultSetString(resultSet, "start_stop_name"),
                            resultSet.getString("target_stop_id"),
                            resultSetString(resultSet, "target_stop_name"),
                            departureSeconds,
                            arrivalSeconds,
                            Math.max(0, (arrivalSeconds - departureSeconds) / 60),
                            resultSet.getInt("start_sequence"),
                            resultSet.getInt("target_sequence"),
                            resultSet.getString("active_reason")
                    ));
                }
            }
        }
        return hits;
    }

    private static String directConnectionCte(Options options, String finalSelect) {
        String weekdayColumn = weekdayColumn(options.date().getDayOfWeek());
        return """
                WITH
                calendar_active AS (
                    SELECT service_id
                    FROM calendar
                    WHERE start_date <= ?
                      AND end_date >= ?
                      AND %s = 1
                ),
                service_additions AS (
                    SELECT service_id
                    FROM calendar_dates
                    WHERE date = ? AND exception_type = 1
                ),
                service_removals AS (
                    SELECT service_id
                    FROM calendar_dates
                    WHERE date = ? AND exception_type = 2
                ),
                active_service_ids AS (
                    SELECT service_id FROM calendar_active
                    UNION
                    SELECT service_id FROM service_additions
                ),
                active_services AS (
                    SELECT active_service_ids.service_id,
                           CASE
                               WHEN service_additions.service_id IS NOT NULL THEN 'calendar_dates_addition'
                               ELSE 'calendar'
                           END AS active_reason
                    FROM active_service_ids
                    LEFT JOIN service_additions ON service_additions.service_id = active_service_ids.service_id
                    LEFT JOIN service_removals ON service_removals.service_id = active_service_ids.service_id
                    WHERE service_removals.service_id IS NULL
                ),
                direct_candidates AS (
                    SELECT
                        start_time.trip_id,
                        trips.route_id,
                        trips.service_id,
                        routes.route_short_name,
                        routes.route_long_name,
                        start_time.stop_id AS start_stop_id,
                        start_stop.stop_name AS start_stop_name,
                        target_time.stop_id AS target_stop_id,
                        target_stop.stop_name AS target_stop_name,
                        start_time.departure_seconds AS start_departure_seconds,
                        target_time.arrival_seconds AS target_arrival_seconds,
                        start_time.stop_sequence AS start_sequence,
                        target_time.stop_sequence AS target_sequence
                    FROM stop_area_members start_member
                    JOIN stop_times start_time
                      ON start_time.stop_id = start_member.stop_id
                    JOIN stop_times target_time
                      ON target_time.trip_id = start_time.trip_id
                     AND target_time.stop_sequence > start_time.stop_sequence
                    JOIN stop_area_members target_member
                      ON target_member.stop_id = target_time.stop_id
                     AND target_member.area_id = ?
                    JOIN trips ON trips.trip_id = start_time.trip_id
                    LEFT JOIN routes ON routes.route_id = trips.route_id
                    LEFT JOIN stops start_stop ON start_stop.stop_id = start_time.stop_id
                    LEFT JOIN stops target_stop ON target_stop.stop_id = target_time.stop_id
                    WHERE start_member.area_id = ?
                      AND start_time.departure_seconds >= ?
                      AND start_time.departure_seconds <= ?
                      AND target_time.arrival_seconds >= start_time.departure_seconds
                )
                %s
                """.formatted(weekdayColumn, finalSelect);
    }

    private static int bindCommonParameters(PreparedStatement statement, Options options) throws SQLException {
        String dateText = GTFS_DATE_FORMAT.format(options.date());
        int index = 1;
        statement.setString(index++, dateText);
        statement.setString(index++, dateText);
        statement.setString(index++, dateText);
        statement.setString(index++, dateText);
        statement.setString(index++, options.targetAreaId());
        statement.setString(index++, options.startAreaId());
        statement.setInt(index++, toSeconds(options.from()));
        statement.setInt(index++, toSeconds(options.to()));
        return index;
    }

    private static String weekdayColumn(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "monday";
            case TUESDAY -> "tuesday";
            case WEDNESDAY -> "wednesday";
            case THURSDAY -> "thursday";
            case FRIDAY -> "friday";
            case SATURDAY -> "saturday";
            case SUNDAY -> "sunday";
        };
    }

    private static void printReport(
            Connection connection,
            Options options,
            DirectConnectionCounts counts,
            List<DirectConnectionHit> hits,
            long elapsedMs
    ) throws SQLException {
        StopArea startArea = loadStopArea(connection, options.startAreaId());
        StopArea targetArea = loadStopArea(connection, options.targetAreaId());
        System.out.println("IXIT SQLite Direct Connection Diagnostics");
        System.out.println("database=" + options.database().toAbsolutePath());
        System.out.println("contract_version=" + SqliteContract.CONTRACT_VERSION);
        System.out.println("date=" + options.date());
        System.out.println("window=" + options.from() + "-" + options.to()
                + " seconds=" + toSeconds(options.from()) + "-" + toSeconds(options.to()));
        System.out.println("start_area=" + options.startAreaId() + " " + startArea.name());
        System.out.println("target_area=" + options.targetAreaId() + " " + targetArea.name());
        System.out.println("mode=query_native_read_only_direct_connections");
        System.out.println("elapsed_ms=" + elapsedMs);
        System.out.println("raw_direct_candidates=" + counts.rawCount());
        System.out.println("active_direct_candidates=" + counts.activeCount());
        System.out.println("calendar_dates_added_candidates=" + counts.calendarDatesAddedCount());
        System.out.println("calendar_dates_removed_candidates=" + counts.calendarDatesRemovedCount());
        System.out.println("inactive_filtered_candidates=" + Math.max(0, counts.rawCount() - counts.activeCount()));
        System.out.println("returned=" + hits.size());
        System.out.println();

        for (DirectConnectionHit hit : hits) {
            System.out.println("- trip_id=" + hit.tripId()
                    + " route_id=" + hit.routeId()
                    + " route=" + displayRoute(hit)
                    + " service_id=" + hit.serviceId()
                    + " service_active_reason=" + hit.serviceActiveReason());
            System.out.println("  start=" + hit.startStopId()
                    + " " + hit.startStopName()
                    + " dep=" + formatSeconds(hit.startDepartureSeconds())
                    + " seq=" + hit.startSequence());
            System.out.println("  target=" + hit.targetStopId()
                    + " " + hit.targetStopName()
                    + " arr=" + formatSeconds(hit.targetArrivalSeconds())
                    + " seq=" + hit.targetSequence()
                    + " duration_min=" + hit.durationMinutes());
        }
    }

    private static StopArea loadStopArea(Connection connection, String areaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT area_name FROM stop_areas WHERE area_id = ?")) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return new StopArea(areaId, resultSetString(resultSet, "area_name"));
                }
            }
        }
        return new StopArea(areaId, "<unresolved>");
    }

    private static String displayRoute(DirectConnectionHit hit) {
        if (!hit.routeShortName().isBlank()) {
            return hit.routeShortName();
        }
        if (!hit.routeLongName().isBlank()) {
            return hit.routeLongName();
        }
        return "-";
    }

    private static int toSeconds(LocalTime time) {
        return time.getHour() * 3600 + time.getMinute() * 60 + time.getSecond();
    }

    private static String formatSeconds(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remainingSeconds = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static String resultSetString(ResultSet resultSet, String columnName) throws SQLException {
        String value = resultSet.getString(columnName);
        return value == null ? "" : value;
    }

    private record Options(
            Path database,
            LocalDate date,
            String startAreaId,
            String targetAreaId,
            LocalTime from,
            LocalTime to,
            int limit
    ) {
        private static Options parse(String[] args) {
            Path database = Path.of("build", "gtfs-de-full-core-v0_5.sqlite");
            LocalDate date = LocalDate.now();
            String startAreaId = null;
            String targetAreaId = null;
            LocalTime from = LocalTime.of(5, 0);
            LocalTime to = LocalTime.of(7, 0);
            int limit = DEFAULT_LIMIT;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--database" -> database = Path.of(requireValue(args, ++i, arg));
                    case "--date" -> date = LocalDate.parse(requireValue(args, ++i, arg));
                    case "--start-area" -> startAreaId = requireValue(args, ++i, arg);
                    case "--target-area" -> targetAreaId = requireValue(args, ++i, arg);
                    case "--from" -> from = LocalTime.parse(requireValue(args, ++i, arg));
                    case "--to" -> to = LocalTime.parse(requireValue(args, ++i, arg));
                    case "--limit" -> limit = Integer.parseInt(requireValue(args, ++i, arg));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (isBlank(startAreaId)) {
                throw new IllegalArgumentException("--start-area is required");
            }
            if (isBlank(targetAreaId)) {
                throw new IllegalArgumentException("--target-area is required");
            }
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("--to must not be before --from");
            }
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException("--limit must be between 1 and 100");
            }

            return new Options(database, date, startAreaId, targetAreaId, from, to, limit);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record DirectConnectionCounts(
            long rawCount,
            long activeCount,
            long calendarDatesAddedCount,
            long calendarDatesRemovedCount
    ) {
    }

    private record StopArea(String areaId, String name) {
    }

    private record DirectConnectionHit(
            String tripId,
            String routeId,
            String routeShortName,
            String routeLongName,
            String serviceId,
            String startStopId,
            String startStopName,
            String targetStopId,
            String targetStopName,
            int startDepartureSeconds,
            int targetArrivalSeconds,
            int durationMinutes,
            int startSequence,
            int targetSequence,
            String serviceActiveReason
    ) {
    }
}
