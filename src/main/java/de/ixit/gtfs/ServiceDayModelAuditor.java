package de.ixit.gtfs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceDayModelAuditor {
    public static final String MODEL_VERSION = "1";
    private static final int MAX_SAMPLES = 25;

    private ServiceDayModelAuditor() {
    }

    public static ServiceDayModelReport audit(Path databasePath) throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize().toUri() + "?mode=ro&immutable=1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            return audit(connection);
        }
    }

    public static ServiceDayModelReport audit(Connection connection) throws SQLException {
        if (!hasTable(connection, "service_calendar_summary")) {
            return new ServiceDayModelReport(
                    MODEL_VERSION, false, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0,
                    Map.of(), List.of("MISSING_SERVICE_CALENDAR_SUMMARY")
            );
        }

        long serviceCount = count(connection, "SELECT COUNT(*) FROM service_calendar_summary");
        long tripServices = count(connection, "SELECT COUNT(*) FROM service_calendar_summary WHERE trip_count > 0");
        long baseServices = count(connection, "SELECT COUNT(*) FROM service_calendar_summary WHERE has_calendar = 1");
        long exceptionServices = count(connection, "SELECT COUNT(*) FROM service_calendar_summary WHERE addition_count + removal_count > 0");
        long exceptionOnly = count(connection, "SELECT COUNT(*) FROM service_calendar_summary WHERE status = 'EXCEPTIONS_ONLY'");
        long unresolved = count(connection, "SELECT COUNT(*) FROM service_calendar_summary WHERE status = 'UNRESOLVED'");
        long invalidFlags = count(connection, """
                SELECT COUNT(*) FROM calendar
                WHERE monday IS NULL OR monday NOT IN (0, 1)
                   OR tuesday IS NULL OR tuesday NOT IN (0, 1)
                   OR wednesday IS NULL OR wednesday NOT IN (0, 1)
                   OR thursday IS NULL OR thursday NOT IN (0, 1)
                   OR friday IS NULL OR friday NOT IN (0, 1)
                   OR saturday IS NULL OR saturday NOT IN (0, 1)
                   OR sunday IS NULL OR sunday NOT IN (0, 1)
                """);
        long invalidRanges = count(connection, """
                SELECT COUNT(*) FROM calendar
                WHERE start_date IS NULL OR length(start_date) <> 8
                   OR start_date GLOB '*[^0-9]*'
                   OR end_date IS NULL OR length(end_date) <> 8
                   OR end_date GLOB '*[^0-9]*'
                   OR start_date > end_date
                """);
        long invalidExceptionDates = count(connection, """
                SELECT COUNT(*) FROM calendar_dates
                WHERE date IS NULL OR length(date) <> 8 OR date GLOB '*[^0-9]*'
                """);
        long invalidExceptionTypes = count(connection, """
                SELECT COUNT(*) FROM calendar_dates
                WHERE exception_type IS NULL OR exception_type NOT IN (1, 2)
                   OR exception_action NOT IN ('ADDITION', 'REMOVAL')
                """);
        long overflowCount = count(connection, """
                SELECT COUNT(*) FROM stop_times
                WHERE arrival_seconds >= 86400 OR departure_seconds >= 86400
                """);
        long maxSeconds = count(connection, """
                SELECT COALESCE(MAX(MAX(arrival_seconds, departure_seconds)), 0) FROM stop_times
                """);

        Map<String, Long> timezoneCounts = new LinkedHashMap<>();
        try (var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT service_timezone, COUNT(*) AS service_count
                     FROM service_calendar_summary
                     GROUP BY service_timezone
                     ORDER BY service_timezone
                     """)) {
            while (resultSet.next()) {
                timezoneCounts.put(resultSet.getString(1), resultSet.getLong(2));
            }
        }

        long invalidIanaTimezones = 0;
        long unknownTimezoneTripServices = 0;
        long multipleTimezoneTripServices = 0;
        List<String> samples = new ArrayList<>();
        try (var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT service_timezone, COUNT(*) AS service_count
                     FROM service_calendar_summary
                     WHERE trip_count > 0
                     GROUP BY service_timezone
                     ORDER BY service_timezone
                     """)) {
            while (resultSet.next()) {
                String timezone = resultSet.getString(1);
                long serviceCountForTimezone = resultSet.getLong(2);
                if ("UNKNOWN".equals(timezone)) {
                    unknownTimezoneTripServices += serviceCountForTimezone;
                } else if ("MULTIPLE".equals(timezone)) {
                    multipleTimezoneTripServices += serviceCountForTimezone;
                } else if (!isIanaTimezone(timezone)) {
                    invalidIanaTimezones += serviceCountForTimezone;
                    if (samples.size() < MAX_SAMPLES) {
                        samples.add("INVALID_IANA_TIMEZONE:" + timezone);
                    }
                }
            }
        }

        addSamples(connection, samples, "UNRESOLVED", """
                SELECT service_id FROM service_calendar_summary
                WHERE status = 'UNRESOLVED' ORDER BY service_id LIMIT 25
                """);
        addSamples(connection, samples, "UNKNOWN_TIMEZONE", """
                SELECT service_id FROM service_calendar_summary
                WHERE trip_count > 0 AND service_timezone IN ('UNKNOWN', 'MULTIPLE')
                ORDER BY service_id LIMIT 25
                """);

        return new ServiceDayModelReport(
                MODEL_VERSION,
                true,
                serviceCount,
                tripServices,
                baseServices,
                exceptionServices,
                exceptionOnly,
                unresolved,
                invalidFlags,
                invalidRanges,
                invalidExceptionDates,
                invalidExceptionTypes,
                invalidIanaTimezones,
                unknownTimezoneTripServices,
                multipleTimezoneTripServices,
                overflowCount,
                maxSeconds,
                Map.copyOf(timezoneCounts),
                List.copyOf(samples)
        );
    }

    private static boolean isIanaTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return false;
        }
        try {
            ZoneId.of(timezone);
            return ZoneId.getAvailableZoneIds().contains(timezone);
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static void addSamples(Connection connection, List<String> samples, String type, String sql) throws SQLException {
        if (samples.size() >= MAX_SAMPLES) {
            return;
        }
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next() && samples.size() < MAX_SAMPLES) {
                String value = resultSet.getString(1);
                samples.add(type + ":" + value);
            }
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
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
}
