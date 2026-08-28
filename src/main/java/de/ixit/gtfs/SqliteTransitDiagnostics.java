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

public final class SqliteTransitDiagnostics {
    private static final int DEFAULT_TOP_N = 4;
    private static final int DEFAULT_DEPARTURE_LIMIT = 8;
    private static final int TOKEN_MATCH_LIMIT = 8_000;
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final List<String> DEFAULT_QUERIES = List.of(
            "Dortmund Hbf",
            "Gelsenkirchen Hbf",
            "Castrop",
            "Berlin Hauptbahnhof"
    );

    private SqliteTransitDiagnostics() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("SQLite Transit diagnostics failed: " + ex.getMessage());
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
            printHeader(options);
            printCalendarDateSummary(connection, options.date());
            for (String query : options.queries()) {
                diagnoseQuery(connection, query, options);
            }
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
                "stops",
                "stop_areas",
                "stop_area_members",
                "routes",
                "trips",
                "stop_times",
                "calendar",
                "calendar_dates",
                "transfers"
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

    private static void printHeader(Options options) {
        System.out.println("IXIT SQLite Transit Diagnostics");
        System.out.println("database=" + options.database().toAbsolutePath());
        System.out.println("contract_version=" + SqliteContract.CONTRACT_VERSION);
        System.out.println("date=" + options.date());
        System.out.println("window=" + options.from() + "-" + options.to()
                + " seconds=" + toSeconds(options.from()) + "-" + toSeconds(options.to()));
        System.out.println("mode=read_only_diagnostics_not_routing");
        System.out.println();
    }

    private static void printCalendarDateSummary(Connection connection, LocalDate date) throws SQLException {
        String dateText = GTFS_DATE_FORMAT.format(date);
        long total = countRows(connection, "SELECT COUNT(*) FROM calendar_dates");
        long dateRows = countRows(connection, "SELECT COUNT(*) FROM calendar_dates WHERE date = ?", dateText);
        long additions = countRows(
                connection,
                "SELECT COUNT(*) FROM calendar_dates WHERE date = ? AND exception_type = 1",
                dateText
        );
        long removals = countRows(
                connection,
                "SELECT COUNT(*) FROM calendar_dates WHERE date = ? AND exception_type = 2",
                dateText
        );
        System.out.println("calendar_dates_total=" + total);
        System.out.println("calendar_dates_for_date=" + dateRows
                + " additions=" + additions
                + " removals=" + removals);
        System.out.println();
    }

    private static long countRows(Connection connection, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) {
                statement.setString(i + 1, values[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static void diagnoseQuery(Connection connection, String query, Options options) throws SQLException {
        List<AreaHit> hits = resolveAreas(connection, query, options.topN());
        System.out.println("QUERY " + query);
        if (hits.isEmpty()) {
            System.out.println("- no StopArea hit");
            System.out.println();
            return;
        }

        AreaHit area = hits.get(0);
        List<StopMember> members = loadMembers(connection, area.areaId());
        long departureCount = countDepartures(
                connection,
                area.areaId(),
                toSeconds(options.from()),
                toSeconds(options.to())
        );
        List<Departure> departures = loadDepartures(
                connection,
                area.areaId(),
                toSeconds(options.from()),
                toSeconds(options.to()),
                options.departureLimit()
        );

        System.out.println("- area_id=" + area.areaId());
        System.out.println("- area_name=" + area.areaName());
        System.out.println("- score=" + area.score() + " matched_tokens=" + String.join(",", area.matchedTokens()));
        System.out.println("- member_stops=" + members.size() + " sample=" + sampleMembers(members));
        System.out.println("- departures_in_window=" + departureCount + " sample=" + departures.size());

        for (Departure departure : departures) {
            TripDetails trip = loadTripDetails(connection, departure.tripId());
            ServiceDayStatus serviceStatus = loadServiceDayStatus(connection, departure.serviceId(), options.date());
            System.out.println("  - departure="
                    + formatSeconds(departure.departureSeconds())
                    + " stop_id=" + departure.stopId()
                    + " trip_id=" + departure.tripId()
                    + " route_id=" + departure.routeId()
                    + " route=" + nullToDash(departure.routeShortName())
                    + " service_id=" + departure.serviceId()
                    + " service_day=" + serviceStatus.summary()
                    + " trip_stops=" + trip.stopTimes().size()
                    + " trip_sample=" + trip.sample());
        }
        System.out.println();
    }

    private static long countDepartures(
            Connection connection,
            String areaId,
            int fromSeconds,
            int toSeconds
    ) throws SQLException {
        String sql = """
                SELECT COUNT(*)
                FROM stop_area_members sam
                JOIN stop_times st ON st.stop_id = sam.stop_id
                WHERE sam.area_id = ?
                  AND st.departure_seconds >= ?
                  AND st.departure_seconds <= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, areaId);
            statement.setInt(2, fromSeconds);
            statement.setInt(3, toSeconds);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong(1) : 0L;
            }
        }
    }

    private static List<AreaHit> resolveAreas(Connection connection, String rawQuery, int topN) throws SQLException {
        String normalized = StopNameNormalizer.normalize(rawQuery);
        List<String> tokens = queryTokens(normalized);
        Map<String, MutableAreaHit> hits = new LinkedHashMap<>();

        if (!normalized.isBlank()) {
            collectAreaMatches(connection, hits, normalized, normalized, true, false);
        }
        for (String token : tokens) {
            collectAreaMatches(connection, hits, token, token, true, false);
            if (token.length() >= 3) {
                collectAreaMatches(connection, hits, token, token, false, true);
            }
        }

        for (MutableAreaHit hit : hits.values()) {
            if (normalized.equals(hit.areaNameNormalized)) {
                hit.score += 8_000;
            } else if (!normalized.isBlank() && hit.areaNameNormalized.startsWith(normalized)) {
                hit.score += 3_000;
            } else if (!normalized.isBlank() && hit.areaNameNormalized.contains(normalized)) {
                hit.score += 1_200;
            }
            for (String token : tokens) {
                if (hit.areaNameNormalized.contains(token)) {
                    hit.score += 250;
                }
            }
        }

        return hits.values().stream()
                .map(MutableAreaHit::toHit)
                .sorted(Comparator
                        .comparingInt(AreaHit::score).reversed()
                        .thenComparing(AreaHit::areaName)
                        .thenComparing(AreaHit::areaId))
                .limit(topN)
                .toList();
    }

    private static void collectAreaMatches(
            Connection connection,
            Map<String, MutableAreaHit> hits,
            String queryToken,
            String tokenValue,
            boolean exact,
            boolean prefix
    ) throws SQLException {
        String sql;
        if (exact) {
            sql = """
                    SELECT sst.area_id, sst.token, sst.token_type, sst.source,
                           sa.area_name, sa.area_name_normalized, sa.area_lat, sa.area_lon
                    FROM stop_search_tokens sst
                    JOIN stop_areas sa ON sa.area_id = sst.area_id
                    WHERE sst.token = ?
                    LIMIT ?
                    """;
        } else {
            sql = """
                    SELECT sst.area_id, sst.token, sst.token_type, sst.source,
                           sa.area_name, sa.area_name_normalized, sa.area_lat, sa.area_lon
                    FROM stop_search_tokens sst
                    JOIN stop_areas sa ON sa.area_id = sst.area_id
                    WHERE sst.token >= ? AND sst.token < ?
                    ORDER BY sst.token
                    LIMIT ?
                    """;
        }

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tokenValue);
            if (exact) {
                statement.setInt(2, TOKEN_MATCH_LIMIT);
            } else {
                statement.setString(2, nextPrefix(tokenValue));
                statement.setInt(3, TOKEN_MATCH_LIMIT);
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String areaId = resultSet.getString("area_id");
                    MutableAreaHit hit = hits.get(areaId);
                    if (hit == null) {
                        hit = new MutableAreaHit(
                                areaId,
                                resultSetString(resultSet, "area_name"),
                                resultSetString(resultSet, "area_name_normalized"),
                                resultSetDouble(resultSet, "area_lat"),
                                resultSetDouble(resultSet, "area_lon")
                        );
                        hits.put(areaId, hit);
                    }
                    String matchedToken = resultSet.getString("token");
                    hit.matchedTokens.add(matchedToken);
                    if (exact && hit.scoredExactTokens.add(queryToken)) {
                        hit.score += "NORMALIZED".equals(resultSet.getString("token_type")) ? 1_600 : 1_000;
                    } else if (prefix && hit.scoredPrefixTokens.add(queryToken)) {
                        hit.score += 420;
                    }
                    if ("AREA_NAME".equals(resultSet.getString("source"))) {
                        hit.score += 180;
                    }
                }
            }
        }
    }

    private static List<StopMember> loadMembers(Connection connection, String areaId) throws SQLException {
        String sql = """
                SELECT sam.stop_id, s.stop_name
                FROM stop_area_members sam
                LEFT JOIN stops s ON s.stop_id = sam.stop_id
                WHERE sam.area_id = ?
                ORDER BY sam.stop_id
                """;
        List<StopMember> members = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    members.add(new StopMember(
                            resultSet.getString("stop_id"),
                            resultSetString(resultSet, "stop_name")
                    ));
                }
            }
        }
        return members;
    }

    private static List<Departure> loadDepartures(
            Connection connection,
            String areaId,
            int fromSeconds,
            int toSeconds,
            int limit
    ) throws SQLException {
        String sql = """
                SELECT st.stop_id, st.trip_id, st.departure_seconds, st.stop_sequence,
                       tr.route_id, tr.service_id, r.route_short_name, r.route_long_name
                FROM stop_area_members sam
                JOIN stop_times st ON st.stop_id = sam.stop_id
                JOIN trips tr ON tr.trip_id = st.trip_id
                LEFT JOIN routes r ON r.route_id = tr.route_id
                WHERE sam.area_id = ?
                  AND st.departure_seconds >= ?
                  AND st.departure_seconds <= ?
                ORDER BY st.departure_seconds, st.trip_id, st.stop_sequence
                LIMIT ?
                """;
        List<Departure> departures = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, areaId);
            statement.setInt(2, fromSeconds);
            statement.setInt(3, toSeconds);
            statement.setInt(4, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    departures.add(new Departure(
                            resultSet.getString("stop_id"),
                            resultSet.getString("trip_id"),
                            resultSet.getInt("departure_seconds"),
                            resultSet.getInt("stop_sequence"),
                            resultSet.getString("route_id"),
                            resultSet.getString("service_id"),
                            resultSetString(resultSet, "route_short_name"),
                            resultSetString(resultSet, "route_long_name")
                    ));
                }
            }
        }
        return departures;
    }

    private static TripDetails loadTripDetails(Connection connection, String tripId) throws SQLException {
        String sql = """
                SELECT st.stop_id, s.stop_name, st.arrival_seconds, st.departure_seconds, st.stop_sequence
                FROM stop_times st
                LEFT JOIN stops s ON s.stop_id = st.stop_id
                WHERE st.trip_id = ?
                ORDER BY st.stop_sequence
                """;
        List<TripStopTime> stopTimes = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tripId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    stopTimes.add(new TripStopTime(
                            resultSet.getString("stop_id"),
                            resultSetString(resultSet, "stop_name"),
                            resultSet.getInt("departure_seconds"),
                            resultSet.getInt("stop_sequence")
                    ));
                }
            }
        }
        return new TripDetails(stopTimes);
    }

    private static ServiceDayStatus loadServiceDayStatus(
            Connection connection,
            String serviceId,
            LocalDate date
    ) throws SQLException {
        CalendarBaseStatus base = loadCalendarBaseStatus(connection, serviceId, date);
        Integer exceptionType = loadCalendarDateException(connection, serviceId, date);
        boolean active = base.active();
        if (exceptionType != null) {
            if (exceptionType == 1) {
                active = true;
            } else if (exceptionType == 2) {
                active = false;
            }
        }
        return new ServiceDayStatus(base.present(), base.active(), exceptionType, active);
    }

    private static CalendarBaseStatus loadCalendarBaseStatus(
            Connection connection,
            String serviceId,
            LocalDate date
    ) throws SQLException {
        String sql = """
                SELECT monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date
                FROM calendar
                WHERE service_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serviceId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return new CalendarBaseStatus(false, false);
                }
                String startDate = resultSetString(resultSet, "start_date");
                String endDate = resultSetString(resultSet, "end_date");
                boolean inDateRange = !startDate.isBlank()
                        && !endDate.isBlank()
                        && !date.isBefore(parseGtfsDate(startDate))
                        && !date.isAfter(parseGtfsDate(endDate));
                return new CalendarBaseStatus(true, inDateRange && dayActive(resultSet, date.getDayOfWeek()));
            }
        }
    }

    private static Integer loadCalendarDateException(
            Connection connection,
            String serviceId,
            LocalDate date
    ) throws SQLException {
        String sql = """
                SELECT exception_type
                FROM calendar_dates
                WHERE service_id = ? AND date = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, serviceId);
            statement.setString(2, GTFS_DATE_FORMAT.format(date));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("exception_type") : null;
            }
        }
    }

    private static boolean dayActive(ResultSet resultSet, DayOfWeek dayOfWeek) throws SQLException {
        return switch (dayOfWeek) {
            case MONDAY -> resultSet.getInt("monday") == 1;
            case TUESDAY -> resultSet.getInt("tuesday") == 1;
            case WEDNESDAY -> resultSet.getInt("wednesday") == 1;
            case THURSDAY -> resultSet.getInt("thursday") == 1;
            case FRIDAY -> resultSet.getInt("friday") == 1;
            case SATURDAY -> resultSet.getInt("saturday") == 1;
            case SUNDAY -> resultSet.getInt("sunday") == 1;
        };
    }

    private static LocalDate parseGtfsDate(String value) {
        return LocalDate.parse(value, GTFS_DATE_FORMAT);
    }

    private static List<String> queryTokens(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalizedQuery.split(" ")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static String nextPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return "\uFFFF";
        }
        char[] chars = prefix.toCharArray();
        chars[chars.length - 1]++;
        return new String(chars);
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

    private static String sampleMembers(List<StopMember> members) {
        return members.stream()
                .limit(4)
                .map(member -> member.stopId() + ":" + member.stopName())
                .toList()
                .toString();
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String resultSetString(ResultSet resultSet, String columnName) throws SQLException {
        String value = resultSet.getString(columnName);
        return value == null ? "" : value;
    }

    private static double resultSetDouble(ResultSet resultSet, String columnName) throws SQLException {
        double value = resultSet.getDouble(columnName);
        return resultSet.wasNull() ? Double.NaN : value;
    }

    private record Options(
            Path database,
            LocalDate date,
            LocalTime from,
            LocalTime to,
            int topN,
            int departureLimit,
            List<String> queries
    ) {
        private static Options parse(String[] args) {
            Path database = Path.of("build", "gtfs-de-full-core-v0_5.sqlite");
            LocalDate date = LocalDate.now();
            LocalTime from = LocalTime.of(5, 0);
            LocalTime to = LocalTime.of(7, 0);
            int topN = DEFAULT_TOP_N;
            int departureLimit = DEFAULT_DEPARTURE_LIMIT;
            List<String> queries = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--database" -> database = Path.of(requireValue(args, ++i, arg));
                    case "--date" -> date = LocalDate.parse(requireValue(args, ++i, arg));
                    case "--from" -> from = LocalTime.parse(requireValue(args, ++i, arg));
                    case "--to" -> to = LocalTime.parse(requireValue(args, ++i, arg));
                    case "--top-n" -> topN = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--departure-limit" -> departureLimit = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--query" -> queries.add(requireValue(args, ++i, arg));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            if (queries.isEmpty()) {
                queries = DEFAULT_QUERIES;
            }
            if (topN < 1 || topN > 20) {
                throw new IllegalArgumentException("--top-n must be between 1 and 20");
            }
            if (departureLimit < 1 || departureLimit > 50) {
                throw new IllegalArgumentException("--departure-limit must be between 1 and 50");
            }
            if (to.isBefore(from)) {
                throw new IllegalArgumentException("--to must not be before --from");
            }
            return new Options(database, date, from, to, topN, departureLimit, List.copyOf(queries));
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static final class MutableAreaHit {
        private final String areaId;
        private final String areaName;
        private final String areaNameNormalized;
        private final double lat;
        private final double lon;
        private final Set<String> matchedTokens = new LinkedHashSet<>();
        private final Set<String> scoredExactTokens = new LinkedHashSet<>();
        private final Set<String> scoredPrefixTokens = new LinkedHashSet<>();
        private int score;

        private MutableAreaHit(String areaId, String areaName, String areaNameNormalized, double lat, double lon) {
            this.areaId = areaId;
            this.areaName = areaName;
            this.areaNameNormalized = areaNameNormalized;
            this.lat = lat;
            this.lon = lon;
        }

        private AreaHit toHit() {
            return new AreaHit(areaId, areaName, areaNameNormalized, lat, lon, score, List.copyOf(matchedTokens));
        }
    }

    private record AreaHit(
            String areaId,
            String areaName,
            String areaNameNormalized,
            double lat,
            double lon,
            int score,
            List<String> matchedTokens
    ) {
    }

    private record StopMember(String stopId, String stopName) {
    }

    private record Departure(
            String stopId,
            String tripId,
            int departureSeconds,
            int stopSequence,
            String routeId,
            String serviceId,
            String routeShortName,
            String routeLongName
    ) {
    }

    private record TripDetails(List<TripStopTime> stopTimes) {
        private String sample() {
            return stopTimes.stream()
                    .limit(4)
                    .map(stopTime -> stopTime.stopSequence()
                            + ":" + stopTime.stopId()
                            + "@" + formatSeconds(stopTime.departureSeconds()))
                    .toList()
                    .toString();
        }
    }

    private record TripStopTime(String stopId, String stopName, int departureSeconds, int stopSequence) {
    }

    private record CalendarBaseStatus(boolean present, boolean active) {
    }

    private record ServiceDayStatus(boolean calendarPresent, boolean calendarActive, Integer exceptionType, boolean active) {
        private String summary() {
            return "calendar_present=" + calendarPresent
                    + ",calendar_active=" + calendarActive
                    + ",exception=" + (exceptionType == null ? "-" : exceptionType)
                    + ",active=" + active;
        }
    }
}
