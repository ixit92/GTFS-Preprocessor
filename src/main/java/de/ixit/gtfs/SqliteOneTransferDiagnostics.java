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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SqliteOneTransferDiagnostics {
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_MAX_FIRST_LEG_CANDIDATES = 500;
    private static final int DEFAULT_MAX_TRANSFER_AREAS = 25;
    private static final int DEFAULT_MIN_TRANSFER_MINUTES = 3;
    private static final int DEFAULT_MAX_TRANSFER_WAIT_MINUTES = 45;
    private static final int SECOND_LEG_LIMIT_PER_TRANSFER_AREA = 20;

    private SqliteOneTransferDiagnostics() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("SQLite one-transfer diagnostics failed: " + ex.getMessage());
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
            FirstLegCounts firstLegCounts = countFirstLegCandidates(connection, options);
            List<FirstLegCandidate> firstLegCandidates = loadFirstLegCandidates(connection, options);
            List<FirstLegCandidate> transferCandidates = earliestTransferAreas(firstLegCandidates, options.maxTransferAreas());

            List<OneTransferHit> allHits = new ArrayList<>();
            long secondLegCandidates = 0;
            for (FirstLegCandidate transferCandidate : transferCandidates) {
                List<SecondLegCandidate> secondLegs = loadSecondLegCandidates(connection, transferCandidate, options);
                secondLegCandidates += secondLegs.size();
                for (SecondLegCandidate secondLeg : secondLegs) {
                    allHits.add(toHit(transferCandidate, secondLeg));
                }
            }

            List<OneTransferHit> rankedHits = rankAndLimit(allHits, options.limit());
            long elapsedMs = elapsedMillis(startedNanos);
            printReport(
                    connection,
                    options,
                    firstLegCounts,
                    firstLegCandidates,
                    transferCandidates,
                    secondLegCandidates,
                    allHits,
                    rankedHits,
                    elapsedMs
            );
        }
    }

    private static void configureReadOnlyConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    private static void validateContract(Connection connection) throws SQLException {
        for (String table : List.of(
                "ixit_metadata",
                "stop_areas",
                "stop_area_members",
                "stops",
                "routes",
                "trips",
                "stop_times",
                "calendar",
                "calendar_dates"
        )) {
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
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
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

    private static FirstLegCounts countFirstLegCandidates(Connection connection, Options options) throws SQLException {
        String sql = activeServicesCte(options.date()) + """
                ,
                first_leg_candidates AS (
                    SELECT first_trip.service_id
                    FROM stop_area_members start_member
                    JOIN stop_times start_time
                      ON start_time.stop_id = start_member.stop_id
                    JOIN stop_times transfer_time
                      ON transfer_time.trip_id = start_time.trip_id
                     AND transfer_time.stop_sequence > start_time.stop_sequence
                    JOIN stop_area_members transfer_member
                      ON transfer_member.stop_id = transfer_time.stop_id
                    JOIN trips first_trip ON first_trip.trip_id = start_time.trip_id
                    WHERE start_member.area_id = ?
                      AND transfer_member.area_id <> ?
                      AND transfer_member.area_id <> ?
                      AND start_time.departure_seconds >= ?
                      AND start_time.departure_seconds <= ?
                      AND transfer_time.arrival_seconds >= start_time.departure_seconds
                )
                SELECT
                    COUNT(*) AS raw_count,
                    SUM(CASE WHEN active_services.service_id IS NOT NULL THEN 1 ELSE 0 END) AS active_count
                FROM first_leg_candidates
                LEFT JOIN active_services ON active_services.service_id = first_leg_candidates.service_id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindActiveServiceParameters(statement, options.date());
            statement.setString(index++, options.startAreaId());
            statement.setString(index++, options.startAreaId());
            statement.setString(index++, options.targetAreaId());
            statement.setInt(index++, toSeconds(options.from()));
            statement.setInt(index, toSeconds(options.to()));
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new FirstLegCounts(0, 0);
                }
                return new FirstLegCounts(resultSet.getLong("raw_count"), resultSet.getLong("active_count"));
            }
        }
    }

    private static List<FirstLegCandidate> loadFirstLegCandidates(Connection connection, Options options) throws SQLException {
        String sql = activeServicesCte(options.date()) + """
                SELECT
                    start_time.trip_id AS first_trip_id,
                    first_trip.route_id AS first_route_id,
                    first_route.route_short_name AS first_route_short_name,
                    first_route.route_long_name AS first_route_long_name,
                    first_trip.service_id AS first_service_id,
                    active_services.active_reason AS first_service_active_reason,
                    start_time.stop_id AS start_stop_id,
                    start_stop.stop_name AS start_stop_name,
                    start_time.departure_seconds AS start_departure_seconds,
                    start_time.stop_sequence AS start_sequence,
                    transfer_member.area_id AS transfer_area_id,
                    transfer_area.area_name AS transfer_area_name,
                    transfer_time.stop_id AS transfer_stop_id,
                    transfer_stop.stop_name AS transfer_stop_name,
                    transfer_time.arrival_seconds AS transfer_arrival_seconds,
                    transfer_time.stop_sequence AS transfer_sequence
                FROM stop_area_members start_member
                JOIN stop_times start_time
                  ON start_time.stop_id = start_member.stop_id
                JOIN trips first_trip ON first_trip.trip_id = start_time.trip_id
                JOIN active_services ON active_services.service_id = first_trip.service_id
                JOIN stop_times transfer_time
                  ON transfer_time.trip_id = start_time.trip_id
                 AND transfer_time.stop_sequence > start_time.stop_sequence
                JOIN stop_area_members transfer_member
                  ON transfer_member.stop_id = transfer_time.stop_id
                JOIN stop_areas transfer_area
                  ON transfer_area.area_id = transfer_member.area_id
                LEFT JOIN routes first_route ON first_route.route_id = first_trip.route_id
                LEFT JOIN stops start_stop ON start_stop.stop_id = start_time.stop_id
                LEFT JOIN stops transfer_stop ON transfer_stop.stop_id = transfer_time.stop_id
                WHERE start_member.area_id = ?
                  AND transfer_member.area_id <> ?
                  AND transfer_member.area_id <> ?
                  AND start_time.departure_seconds >= ?
                  AND start_time.departure_seconds <= ?
                  AND transfer_time.arrival_seconds >= start_time.departure_seconds
                ORDER BY transfer_time.arrival_seconds,
                         start_time.departure_seconds,
                         transfer_member.area_id,
                         start_time.trip_id
                LIMIT ?
                """;
        List<FirstLegCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindActiveServiceParameters(statement, options.date());
            statement.setString(index++, options.startAreaId());
            statement.setString(index++, options.startAreaId());
            statement.setString(index++, options.targetAreaId());
            statement.setInt(index++, toSeconds(options.from()));
            statement.setInt(index++, toSeconds(options.to()));
            statement.setInt(index, options.maxFirstLegCandidates());
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    candidates.add(new FirstLegCandidate(
                            resultSet.getString("first_trip_id"),
                            resultSet.getString("first_route_id"),
                            resultSetString(resultSet, "first_route_short_name"),
                            resultSetString(resultSet, "first_route_long_name"),
                            resultSet.getString("first_service_id"),
                            resultSet.getString("first_service_active_reason"),
                            resultSet.getString("start_stop_id"),
                            resultSetString(resultSet, "start_stop_name"),
                            resultSet.getInt("start_departure_seconds"),
                            resultSet.getInt("start_sequence"),
                            resultSet.getString("transfer_area_id"),
                            resultSetString(resultSet, "transfer_area_name"),
                            resultSet.getString("transfer_stop_id"),
                            resultSetString(resultSet, "transfer_stop_name"),
                            resultSet.getInt("transfer_arrival_seconds"),
                            resultSet.getInt("transfer_sequence")
                    ));
                }
            }
        }
        return candidates;
    }

    private static List<FirstLegCandidate> earliestTransferAreas(List<FirstLegCandidate> candidates, int maxTransferAreas) {
        Map<String, FirstLegCandidate> earliestByArea = new LinkedHashMap<>();
        for (FirstLegCandidate candidate : candidates) {
            FirstLegCandidate previous = earliestByArea.get(candidate.transferAreaId());
            if (previous == null || firstLegBetter(candidate, previous)) {
                earliestByArea.put(candidate.transferAreaId(), candidate);
            }
        }
        return earliestByArea.values().stream()
                .sorted(Comparator
                        .comparingInt(FirstLegCandidate::transferArrivalSeconds)
                        .thenComparingInt(FirstLegCandidate::startDepartureSeconds)
                        .thenComparing(FirstLegCandidate::transferAreaName)
                        .thenComparing(FirstLegCandidate::transferAreaId))
                .limit(maxTransferAreas)
                .toList();
    }

    private static boolean firstLegBetter(FirstLegCandidate candidate, FirstLegCandidate previous) {
        if (candidate.transferArrivalSeconds() != previous.transferArrivalSeconds()) {
            return candidate.transferArrivalSeconds() < previous.transferArrivalSeconds();
        }
        return candidate.startDepartureSeconds() > previous.startDepartureSeconds();
    }

    private static List<SecondLegCandidate> loadSecondLegCandidates(
            Connection connection,
            FirstLegCandidate firstLeg,
            Options options
    ) throws SQLException {
        String sql = activeServicesCte(options.date()) + """
                SELECT
                    second_time.trip_id AS second_trip_id,
                    second_trip.route_id AS second_route_id,
                    second_route.route_short_name AS second_route_short_name,
                    second_route.route_long_name AS second_route_long_name,
                    second_trip.service_id AS second_service_id,
                    active_services.active_reason AS second_service_active_reason,
                    second_time.stop_id AS transfer_departure_stop_id,
                    transfer_departure_stop.stop_name AS transfer_departure_stop_name,
                    second_time.departure_seconds AS transfer_departure_seconds,
                    second_time.stop_sequence AS second_start_sequence,
                    target_time.stop_id AS target_stop_id,
                    target_stop.stop_name AS target_stop_name,
                    target_time.arrival_seconds AS target_arrival_seconds,
                    target_time.stop_sequence AS target_sequence
                FROM stop_area_members transfer_member
                JOIN stop_times second_time
                  ON second_time.stop_id = transfer_member.stop_id
                JOIN trips second_trip ON second_trip.trip_id = second_time.trip_id
                JOIN active_services ON active_services.service_id = second_trip.service_id
                JOIN stop_times target_time
                  ON target_time.trip_id = second_time.trip_id
                 AND target_time.stop_sequence > second_time.stop_sequence
                JOIN stop_area_members target_member
                  ON target_member.stop_id = target_time.stop_id
                 AND target_member.area_id = ?
                LEFT JOIN routes second_route ON second_route.route_id = second_trip.route_id
                LEFT JOIN stops transfer_departure_stop ON transfer_departure_stop.stop_id = second_time.stop_id
                LEFT JOIN stops target_stop ON target_stop.stop_id = target_time.stop_id
                WHERE transfer_member.area_id = ?
                  AND second_time.departure_seconds >= ?
                  AND second_time.departure_seconds <= ?
                  AND target_time.arrival_seconds >= second_time.departure_seconds
                ORDER BY second_time.departure_seconds,
                         target_time.arrival_seconds,
                         second_time.trip_id
                LIMIT ?
                """;
        int earliestDeparture = firstLeg.transferArrivalSeconds() + options.minTransferMinutes() * 60;
        int latestDeparture = firstLeg.transferArrivalSeconds() + options.maxTransferWaitMinutes() * 60;
        List<SecondLegCandidate> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindActiveServiceParameters(statement, options.date());
            statement.setString(index++, options.targetAreaId());
            statement.setString(index++, firstLeg.transferAreaId());
            statement.setInt(index++, earliestDeparture);
            statement.setInt(index++, latestDeparture);
            statement.setInt(index, SECOND_LEG_LIMIT_PER_TRANSFER_AREA);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    int transferDepartureSeconds = resultSet.getInt("transfer_departure_seconds");
                    int targetArrivalSeconds = resultSet.getInt("target_arrival_seconds");
                    candidates.add(new SecondLegCandidate(
                            resultSet.getString("second_trip_id"),
                            resultSet.getString("second_route_id"),
                            resultSetString(resultSet, "second_route_short_name"),
                            resultSetString(resultSet, "second_route_long_name"),
                            resultSet.getString("second_service_id"),
                            resultSet.getString("second_service_active_reason"),
                            resultSet.getString("transfer_departure_stop_id"),
                            resultSetString(resultSet, "transfer_departure_stop_name"),
                            transferDepartureSeconds,
                            resultSet.getInt("second_start_sequence"),
                            resultSet.getString("target_stop_id"),
                            resultSetString(resultSet, "target_stop_name"),
                            targetArrivalSeconds,
                            resultSet.getInt("target_sequence"),
                            Math.max(0, (transferDepartureSeconds - firstLeg.transferArrivalSeconds()) / 60)
                    ));
                }
            }
        }
        return candidates;
    }

    private static OneTransferHit toHit(FirstLegCandidate firstLeg, SecondLegCandidate secondLeg) {
        return new OneTransferHit(
                firstLeg.firstTripId(),
                firstLeg.firstRouteId(),
                firstLeg.firstRouteShortName(),
                firstLeg.firstRouteLongName(),
                firstLeg.firstServiceId(),
                firstLeg.firstServiceActiveReason(),
                secondLeg.secondTripId(),
                secondLeg.secondRouteId(),
                secondLeg.secondRouteShortName(),
                secondLeg.secondRouteLongName(),
                secondLeg.secondServiceId(),
                secondLeg.secondServiceActiveReason(),
                firstLeg.startStopId(),
                firstLeg.startStopName(),
                firstLeg.startDepartureSeconds(),
                firstLeg.transferAreaId(),
                firstLeg.transferAreaName(),
                firstLeg.transferStopId(),
                firstLeg.transferStopName(),
                firstLeg.transferArrivalSeconds(),
                secondLeg.transferDepartureStopId(),
                secondLeg.transferDepartureStopName(),
                secondLeg.transferDepartureSeconds(),
                secondLeg.targetStopId(),
                secondLeg.targetStopName(),
                secondLeg.targetArrivalSeconds(),
                secondLeg.transferWaitMinutes(),
                Math.max(0, (secondLeg.targetArrivalSeconds() - firstLeg.startDepartureSeconds()) / 60)
        );
    }

    private static List<OneTransferHit> rankAndLimit(List<OneTransferHit> hits, int limit) {
        Set<String> seen = new LinkedHashSet<>();
        List<OneTransferHit> deduped = new ArrayList<>();
        hits.stream()
                .sorted(Comparator
                        .comparingInt(OneTransferHit::startDepartureSeconds)
                        .thenComparingInt(OneTransferHit::targetArrivalSeconds)
                        .thenComparingInt(OneTransferHit::transferWaitMinutes)
                        .thenComparing(OneTransferHit::transferAreaName))
                .forEach(hit -> {
                    String key = hit.firstTripId()
                            + "|" + hit.secondTripId()
                            + "|" + hit.transferAreaId()
                            + "|" + hit.startDepartureSeconds()
                            + "|" + hit.targetArrivalSeconds();
                    if (seen.add(key)) {
                        deduped.add(hit);
                    }
                });
        return deduped.stream().limit(limit).toList();
    }

    private static String activeServicesCte(LocalDate date) {
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
                )
                """.formatted(weekdayColumn(date.getDayOfWeek()));
    }

    private static int bindActiveServiceParameters(PreparedStatement statement, LocalDate date) throws SQLException {
        String dateText = GTFS_DATE_FORMAT.format(date);
        int index = 1;
        statement.setString(index++, dateText);
        statement.setString(index++, dateText);
        statement.setString(index++, dateText);
        statement.setString(index++, dateText);
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
            FirstLegCounts firstLegCounts,
            List<FirstLegCandidate> firstLegCandidates,
            List<FirstLegCandidate> transferCandidates,
            long secondLegCandidates,
            List<OneTransferHit> allHits,
            List<OneTransferHit> rankedHits,
            long elapsedMs
    ) throws SQLException {
        StopArea startArea = loadStopArea(connection, options.startAreaId());
        StopArea targetArea = loadStopArea(connection, options.targetAreaId());
        System.out.println("IXIT SQLite One-Transfer Diagnostics");
        System.out.println("database=" + options.database().toAbsolutePath());
        System.out.println("contract_version=" + SqliteContract.CONTRACT_VERSION);
        System.out.println("date=" + options.date());
        System.out.println("window=" + options.from() + "-" + options.to()
                + " seconds=" + toSeconds(options.from()) + "-" + toSeconds(options.to()));
        System.out.println("start_area=" + options.startAreaId() + " " + startArea.name());
        System.out.println("target_area=" + options.targetAreaId() + " " + targetArea.name());
        System.out.println("limits=maxFirstLegCandidates=" + options.maxFirstLegCandidates()
                + ",maxTransferAreas=" + options.maxTransferAreas()
                + ",minTransferMinutes=" + options.minTransferMinutes()
                + ",maxTransferWaitMinutes=" + options.maxTransferWaitMinutes()
                + ",secondLegLimitPerTransferArea=" + SECOND_LEG_LIMIT_PER_TRANSFER_AREA
                + ",limit=" + options.limit());
        System.out.println("mode=query_native_read_only_one_transfer");
        System.out.println("elapsed_ms=" + elapsedMs);
        System.out.println("first_leg_raw_candidates=" + firstLegCounts.rawCount());
        System.out.println("first_leg_active_candidates=" + firstLegCounts.activeCount());
        System.out.println("first_leg_loaded=" + firstLegCandidates.size());
        System.out.println("first_leg_limit_hit=" + (firstLegCandidates.size() >= options.maxFirstLegCandidates()));
        System.out.println("transfer_area_candidates_before_limit=" + distinctTransferAreas(firstLegCandidates));
        System.out.println("transfer_area_candidates=" + transferCandidates.size());
        System.out.println("second_leg_candidates=" + secondLegCandidates);
        System.out.println("active_results=" + allHits.size());
        System.out.println("returned=" + rankedHits.size());
        System.out.println();

        for (OneTransferHit hit : rankedHits) {
            System.out.println("- transfer_area=" + hit.transferAreaId()
                    + " " + hit.transferAreaName()
                    + " total_duration_min=" + hit.totalDurationMinutes()
                    + " wait_min=" + hit.transferWaitMinutes());
            System.out.println("  first_trip=" + hit.firstTripId()
                    + " route=" + displayRoute(hit.firstRouteShortName(), hit.firstRouteLongName())
                    + " service=" + hit.firstServiceId()
                    + " reason=" + hit.firstServiceActiveReason());
            System.out.println("  start=" + hit.startStopId()
                    + " " + hit.startStopName()
                    + " dep=" + formatSeconds(hit.startDepartureSeconds()));
            System.out.println("  transfer_arrive=" + hit.transferArrivalStopId()
                    + " " + hit.transferArrivalStopName()
                    + " arr=" + formatSeconds(hit.transferArrivalSeconds()));
            System.out.println("  second_trip=" + hit.secondTripId()
                    + " route=" + displayRoute(hit.secondRouteShortName(), hit.secondRouteLongName())
                    + " service=" + hit.secondServiceId()
                    + " reason=" + hit.secondServiceActiveReason());
            System.out.println("  transfer_depart=" + hit.transferDepartureStopId()
                    + " " + hit.transferDepartureStopName()
                    + " dep=" + formatSeconds(hit.transferDepartureSeconds()));
            System.out.println("  target=" + hit.targetStopId()
                    + " " + hit.targetStopName()
                    + " arr=" + formatSeconds(hit.targetArrivalSeconds()));
        }
    }

    private static long distinctTransferAreas(List<FirstLegCandidate> candidates) {
        return candidates.stream().map(FirstLegCandidate::transferAreaId).distinct().count();
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

    private static String displayRoute(String shortName, String longName) {
        if (!isBlank(shortName)) {
            return shortName;
        }
        if (!isBlank(longName)) {
            return longName;
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

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record Options(
            Path database,
            LocalDate date,
            String startAreaId,
            String targetAreaId,
            LocalTime from,
            LocalTime to,
            int limit,
            int maxFirstLegCandidates,
            int maxTransferAreas,
            int minTransferMinutes,
            int maxTransferWaitMinutes
    ) {
        private static Options parse(String[] args) {
            Path database = Path.of("build", "gtfs-de-full-core-v0_5.sqlite");
            LocalDate date = LocalDate.now();
            String startAreaId = null;
            String targetAreaId = null;
            LocalTime from = LocalTime.of(5, 0);
            LocalTime to = LocalTime.of(7, 0);
            int limit = DEFAULT_LIMIT;
            int maxFirstLegCandidates = DEFAULT_MAX_FIRST_LEG_CANDIDATES;
            int maxTransferAreas = DEFAULT_MAX_TRANSFER_AREAS;
            int minTransferMinutes = DEFAULT_MIN_TRANSFER_MINUTES;
            int maxTransferWaitMinutes = DEFAULT_MAX_TRANSFER_WAIT_MINUTES;

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
                    case "--max-first-leg-candidates" -> maxFirstLegCandidates = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--max-transfer-areas" -> maxTransferAreas = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--min-transfer-minutes" -> minTransferMinutes = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--max-transfer-wait-minutes" -> maxTransferWaitMinutes = Integer.parseInt(requireValue(args, ++i, arg));
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
            if (maxFirstLegCandidates < 1 || maxFirstLegCandidates > 20_000) {
                throw new IllegalArgumentException("--max-first-leg-candidates must be between 1 and 20000");
            }
            if (maxTransferAreas < 1 || maxTransferAreas > 500) {
                throw new IllegalArgumentException("--max-transfer-areas must be between 1 and 500");
            }
            if (minTransferMinutes < 0 || minTransferMinutes > 120) {
                throw new IllegalArgumentException("--min-transfer-minutes must be between 0 and 120");
            }
            if (maxTransferWaitMinutes < minTransferMinutes || maxTransferWaitMinutes > 240) {
                throw new IllegalArgumentException("--max-transfer-wait-minutes must be between minTransferMinutes and 240");
            }

            return new Options(
                    database,
                    date,
                    startAreaId,
                    targetAreaId,
                    from,
                    to,
                    limit,
                    maxFirstLegCandidates,
                    maxTransferAreas,
                    minTransferMinutes,
                    maxTransferWaitMinutes
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private record FirstLegCounts(long rawCount, long activeCount) {
    }

    private record StopArea(String areaId, String name) {
    }

    private record FirstLegCandidate(
            String firstTripId,
            String firstRouteId,
            String firstRouteShortName,
            String firstRouteLongName,
            String firstServiceId,
            String firstServiceActiveReason,
            String startStopId,
            String startStopName,
            int startDepartureSeconds,
            int startSequence,
            String transferAreaId,
            String transferAreaName,
            String transferStopId,
            String transferStopName,
            int transferArrivalSeconds,
            int transferSequence
    ) {
    }

    private record SecondLegCandidate(
            String secondTripId,
            String secondRouteId,
            String secondRouteShortName,
            String secondRouteLongName,
            String secondServiceId,
            String secondServiceActiveReason,
            String transferDepartureStopId,
            String transferDepartureStopName,
            int transferDepartureSeconds,
            int secondStartSequence,
            String targetStopId,
            String targetStopName,
            int targetArrivalSeconds,
            int targetSequence,
            int transferWaitMinutes
    ) {
    }

    private record OneTransferHit(
            String firstTripId,
            String firstRouteId,
            String firstRouteShortName,
            String firstRouteLongName,
            String firstServiceId,
            String firstServiceActiveReason,
            String secondTripId,
            String secondRouteId,
            String secondRouteShortName,
            String secondRouteLongName,
            String secondServiceId,
            String secondServiceActiveReason,
            String startStopId,
            String startStopName,
            int startDepartureSeconds,
            String transferAreaId,
            String transferAreaName,
            String transferArrivalStopId,
            String transferArrivalStopName,
            int transferArrivalSeconds,
            String transferDepartureStopId,
            String transferDepartureStopName,
            int transferDepartureSeconds,
            String targetStopId,
            String targetStopName,
            int targetArrivalSeconds,
            int transferWaitMinutes,
            int totalDurationMinutes
    ) {
    }
}
