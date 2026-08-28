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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class SqliteTransitDataAccess implements TransitDataAccess {
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final Connection connection;

    public SqliteTransitDataAccess(Path database) throws SQLException {
        if (database == null || !Files.isRegularFile(database)) {
            throw new IllegalArgumentException("SQLite database not found: "
                    + (database == null ? "<null>" : database.toAbsolutePath()));
        }
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        configureReadOnlyConnection();
        validateContract();
    }

    private void configureReadOnlyConnection() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    private void validateContract() throws SQLException {
        for (String table : List.of(
                "ixit_metadata",
                "stop_areas",
                "stop_area_members",
                "stops",
                "trips",
                "routes",
                "stop_times",
                "calendar",
                "calendar_dates"
        )) {
            if (!tableExists(table)) {
                throw new IllegalArgumentException("Required SQLite table missing: " + table);
            }
        }

        String contractVersion = metadataValue("contract_version");
        if (!SqliteContract.SUPPORTED_CONTRACT_VERSIONS.contains(contractVersion)) {
            throw new IllegalArgumentException("Unsupported contract_version: " + contractVersion
                    + " supported " + SqliteContract.SUPPORTED_CONTRACT_VERSIONS);
        }
    }

    private boolean tableExists(String tableName) throws SQLException {
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

    private String metadataValue(String key) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT value FROM ixit_metadata WHERE key = ?")) {
            statement.setString(1, key);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("value") : null;
            }
        }
    }

    @Override
    public ResolvedStopAreaData resolveStopArea(String areaId) throws SQLException {
        if (isBlank(areaId)) {
            throw new IllegalArgumentException("areaId must not be blank");
        }

        String sql = """
                SELECT area_id, area_name, area_lat, area_lon
                FROM stop_areas
                WHERE area_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new ResolvedStopAreaData(
                        resultSet.getString("area_id"),
                        resultSetString(resultSet, "area_name"),
                        resultSetDouble(resultSet, "area_lat"),
                        resultSetDouble(resultSet, "area_lon"),
                        getStopAreaMembers(areaId)
                );
            }
        }
    }

    @Override
    public List<StopAreaMemberData> getStopAreaMembers(String areaId) throws SQLException {
        if (isBlank(areaId)) {
            throw new IllegalArgumentException("areaId must not be blank");
        }

        String sql = """
                SELECT sam.area_id, sam.stop_id, s.stop_name
                FROM stop_area_members sam
                LEFT JOIN stops s ON s.stop_id = sam.stop_id
                WHERE sam.area_id = ?
                ORDER BY sam.stop_id
                """;
        List<StopAreaMemberData> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    members.add(new StopAreaMemberData(
                            resultSet.getString("area_id"),
                            resultSet.getString("stop_id"),
                            resultSetString(resultSet, "stop_name")
                    ));
                }
            }
        }
        return members;
    }

    @Override
    public List<TransitDepartureData> findDepartures(
            String areaId,
            int fromSeconds,
            int toSeconds,
            Set<String> activeServiceIds,
            int limit
    ) throws SQLException {
        if (isBlank(areaId)) {
            throw new IllegalArgumentException("areaId must not be blank");
        }
        if (fromSeconds < 0 || toSeconds < fromSeconds) {
            throw new IllegalArgumentException("invalid departure window");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (activeServiceIds == null || activeServiceIds.isEmpty()) {
            return List.of();
        }

        String sql = """
                SELECT sam.area_id, st.stop_id, s.stop_name, st.trip_id, st.departure_seconds, st.stop_sequence,
                       tr.route_id, tr.service_id, r.route_short_name, r.route_long_name
                FROM stop_area_members sam
                JOIN stop_times st ON st.stop_id = sam.stop_id
                JOIN trips tr ON tr.trip_id = st.trip_id
                LEFT JOIN routes r ON r.route_id = tr.route_id
                LEFT JOIN stops s ON s.stop_id = st.stop_id
                WHERE sam.area_id = ?
                  AND st.departure_seconds >= ?
                  AND st.departure_seconds <= ?
                  AND tr.service_id IN (%s)
                ORDER BY st.departure_seconds, st.trip_id, st.stop_sequence
                LIMIT ?
                """.formatted(placeholders(activeServiceIds.size()));

        List<TransitDepartureData> departures = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, areaId);
            statement.setInt(index++, fromSeconds);
            statement.setInt(index++, toSeconds);
            for (String serviceId : activeServiceIds) {
                statement.setString(index++, serviceId);
            }
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    departures.add(new TransitDepartureData(
                            resultSet.getString("area_id"),
                            resultSet.getString("stop_id"),
                            resultSetString(resultSet, "stop_name"),
                            resultSet.getString("trip_id"),
                            resultSet.getString("route_id"),
                            resultSetString(resultSet, "route_short_name"),
                            resultSetString(resultSet, "route_long_name"),
                            resultSet.getString("service_id"),
                            resultSet.getInt("departure_seconds"),
                            resultSet.getInt("stop_sequence")
                    ));
                }
            }
        }
        return departures;
    }

    @Override
    public List<DirectTransitLegData> findDirectLegs(
            String startAreaId,
            String targetAreaId,
            LocalDate date,
            int fromSeconds,
            int toSeconds,
            int limit
    ) throws SQLException {
        if (isBlank(startAreaId) || isBlank(targetAreaId)) {
            throw new IllegalArgumentException("startAreaId and targetAreaId must not be blank");
        }
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        if (fromSeconds < 0 || toSeconds < fromSeconds) {
            throw new IllegalArgumentException("invalid departure window");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }

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
                    SELECT active_service_ids.service_id,
                           CASE
                               WHEN service_additions.service_id IS NOT NULL THEN 'calendar_dates_addition'
                               ELSE 'calendar'
                           END AS active_reason
                    FROM active_service_ids
                    LEFT JOIN service_additions ON service_additions.service_id = active_service_ids.service_id
                    LEFT JOIN service_removals ON service_removals.service_id = active_service_ids.service_id
                    WHERE service_removals.service_id IS NULL
                )
                SELECT
                    start_time.trip_id,
                    trips.route_id,
                    routes.route_short_name,
                    routes.route_long_name,
                    trips.service_id,
                    start_time.stop_id AS start_stop_id,
                    start_stop.stop_name AS start_stop_name,
                    target_time.stop_id AS target_stop_id,
                    target_stop.stop_name AS target_stop_name,
                    start_time.departure_seconds AS start_departure_seconds,
                    target_time.arrival_seconds AS target_arrival_seconds,
                    start_time.stop_sequence AS start_sequence,
                    target_time.stop_sequence AS target_sequence,
                    active_services.active_reason AS service_active_reason
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
                JOIN active_services ON active_services.service_id = trips.service_id
                LEFT JOIN routes ON routes.route_id = trips.route_id
                LEFT JOIN stops start_stop ON start_stop.stop_id = start_time.stop_id
                LEFT JOIN stops target_stop ON target_stop.stop_id = target_time.stop_id
                WHERE start_member.area_id = ?
                  AND start_time.departure_seconds >= ?
                  AND start_time.departure_seconds <= ?
                ORDER BY
                    start_time.departure_seconds,
                    target_time.arrival_seconds,
                    start_time.trip_id,
                    start_time.stop_sequence,
                    target_time.stop_sequence
                LIMIT ?
                """.formatted(weekdayColumn(date.getDayOfWeek()));

        String dateText = GTFS_DATE_FORMAT.format(date);
        List<DirectTransitLegData> legs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, targetAreaId);
            statement.setString(index++, startAreaId);
            statement.setInt(index++, fromSeconds);
            statement.setInt(index++, toSeconds);
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int departureSeconds = resultSet.getInt("start_departure_seconds");
                    int arrivalSeconds = resultSet.getInt("target_arrival_seconds");
                    legs.add(new DirectTransitLegData(
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
                            resultSetString(resultSet, "service_active_reason")
                    ));
                }
            }
        }
        return legs;
    }

    @Override
    public List<NextTransitLegData> findNextLegs(
            String fromAreaId,
            LocalDate date,
            int earliestDepartureSeconds,
            int latestDepartureSeconds,
            int minArrivalAfterDepartureSeconds,
            Set<String> activeServiceIds,
            int limit
    ) throws SQLException {
        if (isBlank(fromAreaId)) {
            throw new IllegalArgumentException("fromAreaId must not be blank");
        }
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        if (earliestDepartureSeconds < 0 || latestDepartureSeconds < earliestDepartureSeconds) {
            throw new IllegalArgumentException("invalid departure window");
        }
        if (minArrivalAfterDepartureSeconds < 0) {
            throw new IllegalArgumentException("minArrivalAfterDepartureSeconds must not be negative");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (activeServiceIds == null || activeServiceIds.isEmpty()) {
            return List.of();
        }

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
                next_leg_candidates AS (
                    SELECT
                        ? AS from_area_id,
                        target_member.area_id AS to_area_id,
                        target_area.area_name AS to_area_name,
                        start_time.trip_id,
                        trips.route_id,
                        routes.route_short_name,
                        routes.route_long_name,
                        trips.service_id,
                        start_time.stop_id AS from_stop_id,
                        from_stop.stop_name AS from_stop_name,
                        target_time.stop_id AS to_stop_id,
                        to_stop.stop_name AS to_stop_name,
                        start_time.departure_seconds AS departure_seconds,
                        target_time.arrival_seconds AS arrival_seconds,
                        start_time.stop_sequence AS from_sequence,
                        target_time.stop_sequence AS to_sequence,
                        active_services.active_reason AS service_active_reason,
                        ROW_NUMBER() OVER (
                            PARTITION BY target_member.area_id
                            ORDER BY
                                target_time.arrival_seconds,
                                start_time.departure_seconds,
                                start_time.trip_id,
                                target_time.stop_sequence
                        ) AS area_rank
                    FROM stop_area_members start_member
                    JOIN stop_times start_time
                      ON start_time.stop_id = start_member.stop_id
                    JOIN stop_times target_time
                      ON target_time.trip_id = start_time.trip_id
                     AND target_time.stop_sequence > start_time.stop_sequence
                    JOIN stop_area_members target_member
                      ON target_member.stop_id = target_time.stop_id
                    JOIN stop_areas target_area
                      ON target_area.area_id = target_member.area_id
                    JOIN trips
                      ON trips.trip_id = start_time.trip_id
                    JOIN active_services
                      ON active_services.service_id = trips.service_id
                    LEFT JOIN routes
                      ON routes.route_id = trips.route_id
                    LEFT JOIN stops from_stop
                      ON from_stop.stop_id = start_time.stop_id
                    LEFT JOIN stops to_stop
                      ON to_stop.stop_id = target_time.stop_id
                    WHERE start_member.area_id = ?
                      AND target_member.area_id <> ?
                      AND start_time.departure_seconds >= ?
                      AND start_time.departure_seconds <= ?
                      AND target_time.arrival_seconds >= start_time.departure_seconds + ?
                      AND trips.service_id IN (%s)
                )
                SELECT *
                FROM next_leg_candidates
                WHERE area_rank = 1
                ORDER BY arrival_seconds, departure_seconds, trip_id, to_area_id
                LIMIT ?
                """.formatted(weekdayColumn(date.getDayOfWeek()), placeholders(activeServiceIds.size()));

        String dateText = GTFS_DATE_FORMAT.format(date);
        List<NextTransitLegData> legs = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, fromAreaId);
            statement.setString(index++, fromAreaId);
            statement.setString(index++, fromAreaId);
            statement.setInt(index++, earliestDepartureSeconds);
            statement.setInt(index++, latestDepartureSeconds);
            statement.setInt(index++, minArrivalAfterDepartureSeconds);
            for (String serviceId : activeServiceIds) {
                statement.setString(index++, serviceId);
            }
            statement.setInt(index, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int departureSeconds = resultSet.getInt("departure_seconds");
                    int arrivalSeconds = resultSet.getInt("arrival_seconds");
                    legs.add(new NextTransitLegData(
                            resultSet.getString("from_area_id"),
                            resultSet.getString("to_area_id"),
                            resultSetString(resultSet, "to_area_name"),
                            resultSet.getString("trip_id"),
                            resultSet.getString("route_id"),
                            resultSetString(resultSet, "route_short_name"),
                            resultSetString(resultSet, "route_long_name"),
                            resultSet.getString("service_id"),
                            resultSet.getString("from_stop_id"),
                            resultSetString(resultSet, "from_stop_name"),
                            resultSet.getString("to_stop_id"),
                            resultSetString(resultSet, "to_stop_name"),
                            departureSeconds,
                            arrivalSeconds,
                            Math.max(0, (arrivalSeconds - departureSeconds) / 60),
                            resultSet.getInt("from_sequence"),
                            resultSet.getInt("to_sequence"),
                            resultSetString(resultSet, "service_active_reason")
                    ));
                }
            }
        }
        return legs;
    }

    @Override
    public List<TripStopTimeData> getTripStopTimes(String tripId) throws SQLException {
        if (isBlank(tripId)) {
            throw new IllegalArgumentException("tripId must not be blank");
        }

        String sql = """
                SELECT st.trip_id, st.stop_id, s.stop_name, sam.area_id,
                       st.arrival_seconds, st.departure_seconds, st.stop_sequence
                FROM stop_times st
                LEFT JOIN stops s ON s.stop_id = st.stop_id
                LEFT JOIN stop_area_members sam ON sam.stop_id = st.stop_id
                WHERE st.trip_id = ?
                ORDER BY st.stop_sequence
                """;
        List<TripStopTimeData> stopTimes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tripId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    stopTimes.add(new TripStopTimeData(
                            resultSet.getString("trip_id"),
                            resultSet.getString("stop_id"),
                            resultSetString(resultSet, "stop_name"),
                            resultSetString(resultSet, "area_id"),
                            resultSet.getInt("arrival_seconds"),
                            resultSet.getInt("departure_seconds"),
                            resultSet.getInt("stop_sequence")
                    ));
                }
            }
        }
        return stopTimes;
    }

    @Override
    public TripMetadataData getTripMetadata(String tripId) throws SQLException {
        if (isBlank(tripId)) {
            throw new IllegalArgumentException("tripId must not be blank");
        }

        String sql = """
                SELECT tr.trip_id, tr.route_id, tr.service_id, r.route_short_name, r.route_long_name
                FROM trips tr
                LEFT JOIN routes r ON r.route_id = tr.route_id
                WHERE tr.trip_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tripId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new TripMetadataData(
                        resultSet.getString("trip_id"),
                        resultSet.getString("route_id"),
                        resultSetString(resultSet, "route_short_name"),
                        resultSetString(resultSet, "route_long_name"),
                        resultSet.getString("service_id")
                );
            }
        }
    }

    @Override
    public Set<String> findActiveServiceIds(LocalDate date) throws SQLException {
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }

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
                )
                SELECT active_service_ids.service_id
                FROM active_service_ids
                LEFT JOIN service_removals ON service_removals.service_id = active_service_ids.service_id
                WHERE service_removals.service_id IS NULL
                ORDER BY active_service_ids.service_id
                """.formatted(weekdayColumn(date.getDayOfWeek()));

        String dateText = GTFS_DATE_FORMAT.format(date);
        Set<String> serviceIds = new LinkedHashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, dateText);
            statement.setString(2, dateText);
            statement.setString(3, dateText);
            statement.setString(4, dateText);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    serviceIds.add(resultSet.getString("service_id"));
                }
            }
        }
        return serviceIds;
    }

    @Override
    public ServiceActiveData getServiceActiveData(String serviceId, LocalDate date) throws SQLException {
        if (isBlank(serviceId)) {
            throw new IllegalArgumentException("serviceId must not be blank");
        }
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }

        String sql = """
                SELECT
                    EXISTS (
                        SELECT 1
                        FROM calendar
                        WHERE service_id = ?
                          AND start_date <= ?
                          AND end_date >= ?
                          AND %s = 1
                    ) AS calendar_active,
                    EXISTS (
                        SELECT 1
                        FROM calendar_dates
                        WHERE service_id = ? AND date = ? AND exception_type = 1
                    ) AS calendar_dates_addition,
                    EXISTS (
                        SELECT 1
                        FROM calendar_dates
                        WHERE service_id = ? AND date = ? AND exception_type = 2
                    ) AS calendar_dates_removal
                """.formatted(weekdayColumn(date.getDayOfWeek()));
        String dateText = GTFS_DATE_FORMAT.format(date);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setString(index++, serviceId);
            statement.setString(index++, dateText);
            statement.setString(index++, dateText);
            statement.setString(index++, serviceId);
            statement.setString(index++, dateText);
            statement.setString(index++, serviceId);
            statement.setString(index, dateText);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                boolean calendarActive = resultSet.getInt("calendar_active") == 1;
                boolean added = resultSet.getInt("calendar_dates_addition") == 1;
                boolean removed = resultSet.getInt("calendar_dates_removal") == 1;
                if (removed) {
                    return new ServiceActiveData(serviceId, false, "calendar_dates_removal");
                }
                if (added) {
                    return new ServiceActiveData(serviceId, true, "calendar_dates_addition");
                }
                if (calendarActive) {
                    return new ServiceActiveData(serviceId, true, "calendar");
                }
                return new ServiceActiveData(serviceId, false, "inactive");
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
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

    private static String resultSetString(ResultSet resultSet, String columnName) throws SQLException {
        String value = resultSet.getString(columnName);
        return value == null ? "" : value;
    }

    private static double resultSetDouble(ResultSet resultSet, String columnName) throws SQLException {
        double value = resultSet.getDouble(columnName);
        return resultSet.wasNull() ? Double.NaN : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
