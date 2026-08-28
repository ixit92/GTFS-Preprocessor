package de.ixit.gtfs;

import de.ixit.gtfs.TransitDataAccess.NextTransitLegData;
import de.ixit.gtfs.TransitDataAccess.ResolvedStopAreaData;

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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SqliteNextLegDiagnostics {
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int DEFAULT_LIMIT = 10;

    private SqliteNextLegDiagnostics() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            System.err.println("SqliteNextLegDiagnostics failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (!Files.isRegularFile(options.database())) {
            throw new IllegalArgumentException("SQLite database not found: " + options.database().toAbsolutePath());
        }

        long startedNanos = System.nanoTime();
        try (SqliteTransitDataAccess dataAccess = new SqliteTransitDataAccess(options.database())) {
            ResolvedStopAreaData fromArea = dataAccess.resolveStopArea(options.fromAreaId());
            if (fromArea == null) {
                throw new IllegalArgumentException("StopArea not found: " + options.fromAreaId());
            }

            Set<String> activeServiceIds = dataAccess.findActiveServiceIds(options.date());
            List<NextTransitLegData> legs = dataAccess.findNextLegs(
                    options.fromAreaId(),
                    options.date(),
                    options.fromSeconds(),
                    options.toSeconds(),
                    options.minArrivalAfterDepartureSeconds(),
                    activeServiceIds,
                    options.limit()
            );
            CandidateCounts counts = countCandidates(options);
            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;

            System.out.println("IXIT SQLite Next Leg Diagnostics");
            System.out.println("database=" + options.database().toAbsolutePath());
            System.out.println("date=" + options.date());
            System.out.println("window=" + formatSeconds(options.fromSeconds()) + "-" + formatSeconds(options.toSeconds()));
            System.out.println("from_area=" + fromArea.areaId() + " " + fromArea.displayName()
                    + " members=" + fromArea.members().size());
            System.out.println("active_services=" + activeServiceIds.size());
            System.out.println("raw_candidates=" + counts.rawCandidates());
            System.out.println("active_candidates=" + counts.activeCandidates());
            System.out.println("active_target_areas=" + counts.activeTargetAreas());
            System.out.println("returned=" + legs.size());
            System.out.println("elapsed_ms=" + elapsedMs);

            for (NextTransitLegData leg : legs) {
                System.out.println("- to_area_id=" + leg.toAreaId()
                        + " to_area_name=" + leg.toAreaName()
                        + " route_id=" + leg.routeId()
                        + " route=" + displayRoute(leg)
                        + " trip_id=" + leg.tripId()
                        + " service_id=" + leg.serviceId()
                        + " service_active_reason=" + leg.serviceActiveReason());
                System.out.println("  from_stop_id=" + leg.fromStopId()
                        + " from_stop_name=" + leg.fromStopName()
                        + " departure=" + formatSeconds(leg.departureSeconds())
                        + " sequence=" + leg.fromSequence());
                System.out.println("  to_stop_id=" + leg.toStopId()
                        + " to_stop_name=" + leg.toStopName()
                        + " arrival=" + formatSeconds(leg.arrivalSeconds())
                        + " sequence=" + leg.toSequence()
                        + " duration_minutes=" + leg.durationMinutes());
            }
        }
    }

    private static CandidateCounts countCandidates(Options options) throws SQLException {
        String sql = """
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
                    SELECT active_service_ids.service_id
                    FROM active_service_ids
                    LEFT JOIN service_removals ON service_removals.service_id = active_service_ids.service_id
                    WHERE service_removals.service_id IS NULL
                )
                SELECT
                    COUNT(*) AS raw_candidates,
                    SUM(CASE WHEN active_services.service_id IS NOT NULL THEN 1 ELSE 0 END) AS active_candidates,
                    COUNT(DISTINCT CASE
                        WHEN active_services.service_id IS NOT NULL THEN target_member.area_id
                        ELSE NULL
                    END) AS active_target_areas
                FROM stop_area_members start_member
                JOIN stop_times start_time
                  ON start_time.stop_id = start_member.stop_id
                JOIN stop_times target_time
                  ON target_time.trip_id = start_time.trip_id
                 AND target_time.stop_sequence > start_time.stop_sequence
                JOIN stop_area_members target_member
                  ON target_member.stop_id = target_time.stop_id
                JOIN trips
                  ON trips.trip_id = start_time.trip_id
                LEFT JOIN active_services
                  ON active_services.service_id = trips.service_id
                WHERE start_member.area_id = ?
                  AND target_member.area_id <> ?
                  AND start_time.departure_seconds >= ?
                  AND start_time.departure_seconds <= ?
                  AND target_time.arrival_seconds >= start_time.departure_seconds + ?
                """.formatted(weekdayColumn(options.date().getDayOfWeek()));

        String dateText = GTFS_DATE_FORMAT.format(options.date());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + options.database().toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
                statement.execute("PRAGMA temp_store = MEMORY");
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                statement.setString(index++, dateText);
                statement.setString(index++, dateText);
                statement.setString(index++, dateText);
                statement.setString(index++, dateText);
                statement.setString(index++, options.fromAreaId());
                statement.setString(index++, options.fromAreaId());
                statement.setInt(index++, options.fromSeconds());
                statement.setInt(index++, options.toSeconds());
                statement.setInt(index, options.minArrivalAfterDepartureSeconds());
                try (ResultSet resultSet = statement.executeQuery()) {
                    resultSet.next();
                    return new CandidateCounts(
                            resultSet.getLong("raw_candidates"),
                            resultSet.getLong("active_candidates"),
                            resultSet.getLong("active_target_areas")
                    );
                }
            }
        }
    }

    private static String displayRoute(NextTransitLegData leg) {
        if (!isBlank(leg.routeShortName())) {
            return leg.routeShortName();
        }
        if (!isBlank(leg.routeLongName())) {
            return leg.routeLongName();
        }
        return "-";
    }

    private static int parseTimeSeconds(String value) {
        String[] parts = value.split(":");
        if (parts.length != 2 && parts.length != 3) {
            throw new IllegalArgumentException("Time must be HH:mm or HH:mm:ss: " + value);
        }
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        int seconds = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
            throw new IllegalArgumentException("Invalid time: " + value);
        }
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static String formatSeconds(int secondsSinceServiceDayStart) {
        int hours = secondsSinceServiceDayStart / 3600;
        int minutes = (secondsSinceServiceDayStart % 3600) / 60;
        int seconds = secondsSinceServiceDayStart % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record CandidateCounts(
            long rawCandidates,
            long activeCandidates,
            long activeTargetAreas
    ) {
    }

    private record Options(
            Path database,
            LocalDate date,
            String fromAreaId,
            int fromSeconds,
            int toSeconds,
            int limit,
            int minArrivalAfterDepartureSeconds
    ) {
        private static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++index]);
            }

            Path database = Path.of(values.getOrDefault("database", "build/gtfs-de-full-core-v0_5.sqlite"));
            LocalDate date = LocalDate.parse(values.getOrDefault("date", LocalDate.now().toString()));
            String fromAreaId = values.get("from-area");
            if (isBlank(fromAreaId)) {
                throw new IllegalArgumentException("--from-area is required");
            }
            int fromSeconds = parseTimeSeconds(values.getOrDefault("from", "05:00"));
            int toSeconds = parseTimeSeconds(values.getOrDefault("to", "07:00"));
            int limit = Integer.parseInt(values.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT)));
            int minArrivalAfterDepartureSeconds = Integer.parseInt(values.getOrDefault(
                    "min-arrival-after-departure-seconds",
                    "0"
            ));
            if (toSeconds < fromSeconds) {
                throw new IllegalArgumentException("--to must not be before --from");
            }
            if (limit < 1 || limit > 500) {
                throw new IllegalArgumentException("--limit must be between 1 and 500");
            }
            if (minArrivalAfterDepartureSeconds < 0) {
                throw new IllegalArgumentException("--min-arrival-after-departure-seconds must not be negative");
            }
            return new Options(database, date, fromAreaId, fromSeconds, toSeconds, limit,
                    minArrivalAfterDepartureSeconds);
        }
    }
}
