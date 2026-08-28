package de.ixit.gtfs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class ServiceDayResolver {
    private static final DateTimeFormatter GTFS_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    private ServiceDayResolver() {
    }

    public static ServiceDayResolution resolve(Path databasePath, String serviceId, LocalDate date) throws SQLException {
        String jdbcUrl = "jdbc:sqlite:" + databasePath.toAbsolutePath().normalize().toUri() + "?mode=ro&immutable=1";
        try (Connection connection = DriverManager.getConnection(jdbcUrl)) {
            return resolve(connection, serviceId, date);
        }
    }

    public static ServiceDayResolution resolve(Connection connection, String serviceId, LocalDate date) throws SQLException {
        String dateText = GTFS_DATE.format(date);
        Summary summary = readSummary(connection, serviceId);
        if (summary == null) {
            return new ServiceDayResolution(serviceId, date, false, "UNKNOWN_SERVICE", 0, "UNKNOWN", false, null);
        }

        Integer exceptionType = readExceptionType(connection, serviceId, dateText);
        if (exceptionType != null) {
            if (exceptionType == 1) {
                return result(summary, serviceId, date, true, "CALENDAR_DATES_ADDITION", exceptionType);
            }
            if (exceptionType == 2) {
                return result(summary, serviceId, date, false, "CALENDAR_DATES_REMOVAL", exceptionType);
            }
            return result(summary, serviceId, date, false, "INVALID_EXCEPTION_TYPE", exceptionType);
        }

        if (!summary.hasCalendar()) {
            return result(summary, serviceId, date, false, "EXCEPTIONS_ONLY_NO_ADDITION", null);
        }
        if (summary.startDate() == null || summary.endDate() == null
                || dateText.compareTo(summary.startDate()) < 0
                || dateText.compareTo(summary.endDate()) > 0) {
            return result(summary, serviceId, date, false, "OUTSIDE_CALENDAR_RANGE", null);
        }

        int bit = switch (date.getDayOfWeek()) {
            case MONDAY -> 1;
            case TUESDAY -> 2;
            case WEDNESDAY -> 4;
            case THURSDAY -> 8;
            case FRIDAY -> 16;
            case SATURDAY -> 32;
            case SUNDAY -> 64;
        };
        boolean active = (summary.weekdayMask() & bit) != 0;
        return result(summary, serviceId, date, active,
                active ? "CALENDAR_WEEKDAY_ACTIVE" : "CALENDAR_WEEKDAY_INACTIVE", null);
    }

    private static ServiceDayResolution result(
            Summary summary,
            String serviceId,
            LocalDate date,
            boolean active,
            String reason,
            Integer exceptionType
    ) {
        return new ServiceDayResolution(
                serviceId,
                date,
                active,
                reason,
                summary.tripCount(),
                summary.timezone(),
                summary.hasCalendar(),
                exceptionType
        );
    }

    private static Summary readSummary(Connection connection, String serviceId) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT has_calendar, weekday_mask, start_date, end_date, trip_count, service_timezone
                FROM service_calendar_summary WHERE service_id = ?
                """)) {
            statement.setString(1, serviceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new Summary(
                        resultSet.getInt("has_calendar") != 0,
                        resultSet.getInt("weekday_mask"),
                        resultSet.getString("start_date"),
                        resultSet.getString("end_date"),
                        resultSet.getLong("trip_count"),
                        resultSet.getString("service_timezone")
                );
            }
        }
    }

    private static Integer readExceptionType(Connection connection, String serviceId, String date) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT exception_type FROM calendar_dates WHERE service_id = ? AND date = ?")) {
            statement.setString(1, serviceId);
            statement.setString(2, date);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? (Integer) resultSet.getObject(1) : null;
            }
        }
    }

    private record Summary(
            boolean hasCalendar,
            int weekdayMask,
            String startDate,
            String endDate,
            long tripCount,
            String timezone
    ) {
    }

    public record ServiceDayResolution(
            String serviceId,
            LocalDate serviceDate,
            boolean active,
            String reason,
            long rawTripCount,
            String serviceTimezone,
            boolean baseCalendarPresent,
            Integer exceptionType
    ) {
        public long activeTripCount() {
            return active ? rawTripCount : 0;
        }
    }
}
