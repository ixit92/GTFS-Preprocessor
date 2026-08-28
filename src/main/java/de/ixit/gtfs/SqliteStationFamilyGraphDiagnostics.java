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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SqliteStationFamilyGraphDiagnostics {
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final int DEFAULT_TOP = 8;

    private SqliteStationFamilyGraphDiagnostics() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("StationFamilyGraph diagnostics failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
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
            System.out.println("IXIT StationFamilyGraph Diagnostics");
            System.out.println("database=" + options.database().toAbsolutePath());
            System.out.println("date=" + options.date());
            System.out.println("window=" + options.from() + "-" + options.to());
            System.out.println();

            Set<String> areaIds = new LinkedHashSet<>(options.areaIds());
            for (String query : options.queries()) {
                printSearch(connection, query, options.top());
                List<SearchHit> hits = search(connection, query, options.top());
                if (!hits.isEmpty()) {
                    areaIds.add(hits.get(0).areaId());
                }
            }
            for (String areaId : areaIds) {
                printFamily(connection, areaId, options);
            }
        }
    }

    private static void configureReadOnlyConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    private static void printSearch(Connection connection, String query, int top) throws SQLException {
        List<SearchHit> hits = search(connection, query, top);
        System.out.println("Search query=\"" + query + "\" normalized=\"" + StopNameNormalizer.normalize(query) + "\"");
        int rank = 1;
        for (SearchHit hit : hits) {
            System.out.println("  #" + rank++
                    + " area=" + hit.areaId()
                    + " name=\"" + hit.name() + "\""
                    + " score=" + hit.score()
                    + " profile=" + hit.profileClass()
                    + " rail=" + hit.hasRailService()
                    + " busOnly=" + hit.busOnly()
                    + " lines=\"" + dash(hit.lineLabels()) + "\""
                    + " signals=" + hit.signals());
        }
        if (hits.isEmpty()) {
            System.out.println("  no hits");
        }
        System.out.println();
    }

    private static List<SearchHit> search(Connection connection, String query, int top) throws SQLException {
        String normalized = StopNameNormalizer.normalize(query);
        List<String> tokens = queryTokens(normalized);
        Map<String, MutableSearchHit> hits = new LinkedHashMap<>();
        collectCanonicalNameHits(connection, hits, normalized);
        collectAliasHits(connection, hits, normalized);
        collectTokenHits(connection, hits, tokens);
        return hits.values().stream()
                .map(MutableSearchHit::toHit)
                .sorted((left, right) -> {
                    int scoreCompare = Integer.compare(right.score(), left.score());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    int nameCompare = left.name().compareTo(right.name());
                    if (nameCompare != 0) {
                        return nameCompare;
                    }
                    return left.areaId().compareTo(right.areaId());
                })
                .limit(top)
                .toList();
    }

    private static void collectCanonicalNameHits(
            Connection connection,
            Map<String, MutableSearchHit> hits,
            String normalized
    ) throws SQLException {
        if (normalized.isBlank() || !tableExists(connection, "canonical_stop_area_names")) {
            return;
        }
        String sql = """
                SELECT canonical.primary_stop_area_id AS area_id,
                       names.display_name AS area_name,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.search_priority_score, 0) AS profile_score,
                       COALESCE(profile.line_labels, '') AS line_labels,
                       names.display_name_normalized
                FROM canonical_stop_area_names names
                JOIN canonical_stop_areas canonical ON canonical.canonical_area_id = names.canonical_area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = canonical.primary_stop_area_id
                WHERE names.display_name_normalized = ?
                   OR names.display_name_normalized LIKE ?
                LIMIT 100
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setString(2, normalized + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MutableSearchHit hit = hit(hits, resultSet);
                    hit.score += (normalized.equals(resultSet.getString("display_name_normalized")) ? 45_000 : 18_000)
                            + resultSet.getInt("profile_score");
                    hit.signals.add("canonicalName");
                }
            }
        }
    }

    private static void collectAliasHits(
            Connection connection,
            Map<String, MutableSearchHit> hits,
            String normalized
    ) throws SQLException {
        if (normalized.isBlank() || !tableExists(connection, "stop_area_aliases")) {
            return;
        }
        String sql = """
                SELECT alias.area_id,
                       area.area_name,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.search_priority_score, 0) AS profile_score,
                       COALESCE(profile.line_labels, '') AS line_labels,
                       alias.alias_normalized,
                       alias.alias_type,
                       alias.priority
                FROM stop_area_aliases alias
                JOIN stop_areas area ON area.area_id = alias.area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = alias.area_id
                WHERE alias.alias_normalized = ?
                   OR alias.alias_normalized LIKE ?
                LIMIT 100
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setString(2, normalized + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MutableSearchHit hit = hit(hits, resultSet);
                    hit.score += (normalized.equals(resultSet.getString("alias_normalized")) ? 20_000 : 8_000)
                            + resultSet.getInt("priority") * 100
                            + resultSet.getInt("profile_score");
                    hit.signals.add("alias:" + resultSet.getString("alias_type"));
                }
            }
        }
    }

    private static void collectTokenHits(
            Connection connection,
            Map<String, MutableSearchHit> hits,
            List<String> tokens
    ) throws SQLException {
        if (tokens.isEmpty() || !tableExists(connection, "stop_search_tokens")) {
            return;
        }
        String sql = """
                SELECT token.area_id,
                       area.area_name,
                       COUNT(DISTINCT token.token) AS matched_token_count,
                       GROUP_CONCAT(DISTINCT token.token) AS matched_tokens,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.search_priority_score, 0) AS profile_score,
                       COALESCE(profile.line_labels, '') AS line_labels
                FROM stop_search_tokens token
                JOIN stop_areas area ON area.area_id = token.area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = token.area_id
                WHERE token.token IN (%s)
                GROUP BY token.area_id
                ORDER BY matched_token_count DESC, profile_score DESC, area.area_name
                LIMIT 160
                """.formatted(placeholders(tokens.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < tokens.size(); index++) {
                statement.setString(index + 1, tokens.get(index));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MutableSearchHit hit = hit(hits, resultSet);
                    hit.score += resultSet.getInt("matched_token_count") * 2_000
                            + resultSet.getInt("profile_score");
                    hit.signals.add("tokens:" + dash(resultSet.getString("matched_tokens")));
                }
            }
        }
    }

    private static MutableSearchHit hit(Map<String, MutableSearchHit> hits, ResultSet resultSet) throws SQLException {
        String areaId = resultSet.getString("area_id");
        MutableSearchHit hit = hits.get(areaId);
        if (hit == null) {
            hit = new MutableSearchHit(
                    areaId,
                    resultSet.getString("area_name"),
                    resultSet.getString("profile_class"),
                    resultSet.getInt("has_rail_service") != 0,
                    resultSet.getInt("bus_only") != 0,
                    resultSet.getString("line_labels")
            );
            hits.put(areaId, hit);
        }
        return hit;
    }

    private static void printFamily(Connection connection, String areaId, Options options) throws SQLException {
        System.out.println("Family for requested area=" + areaId);
        AreaHeader header = areaHeader(connection, areaId);
        if (header == null) {
            System.out.println("  area not found");
            System.out.println();
            return;
        }
        System.out.println("  requestedName=\"" + header.name() + "\" profile=" + header.profileClass()
                + " rail=" + header.hasRailService() + " busOnly=" + header.busOnly()
                + " lines=\"" + dash(header.lineLabels()) + "\"");
        String canonicalAreaId = canonicalAreaId(connection, areaId);
        if (canonicalAreaId == null) {
            System.out.println("  no canonical family");
            System.out.println();
            return;
        }
        printCanonicalHeader(connection, canonicalAreaId);
        printMembers(connection, canonicalAreaId, options);
        printInternalEdges(connection, canonicalAreaId);
        System.out.println();
    }

    private static AreaHeader areaHeader(Connection connection, String areaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT area.area_id,
                       area.area_name,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.line_labels, '') AS line_labels
                FROM stop_areas area
                LEFT JOIN stop_area_profiles profile ON profile.area_id = area.area_id
                WHERE area.area_id = ?
                """)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new AreaHeader(
                        resultSet.getString("area_id"),
                        resultSet.getString("area_name"),
                        resultSet.getString("profile_class"),
                        resultSet.getInt("has_rail_service") != 0,
                        resultSet.getInt("bus_only") != 0,
                        resultSet.getString("line_labels")
                );
            }
        }
    }

    private static String canonicalAreaId(Connection connection, String areaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_area_id
                FROM canonical_stop_area_members
                WHERE area_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("canonical_area_id");
                }
            }
        }
        return null;
    }

    private static void printCanonicalHeader(Connection connection, String canonicalAreaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_area_id,
                       canonical_display_name,
                       primary_stop_area_id,
                       profile_class,
                       has_rail_service,
                       line_labels,
                       member_count,
                       display_quality,
                       source,
                       explanation
                FROM canonical_stop_areas
                WHERE canonical_area_id = ?
                """)) {
            statement.setString(1, canonicalAreaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    System.out.println("  canonical=" + resultSet.getString("canonical_area_id")
                            + " name=\"" + resultSet.getString("canonical_display_name") + "\""
                            + " primary=" + resultSet.getString("primary_stop_area_id")
                            + " profile=" + resultSet.getString("profile_class")
                            + " rail=" + (resultSet.getInt("has_rail_service") != 0)
                            + " members=" + resultSet.getInt("member_count")
                            + " quality=" + resultSet.getString("display_quality")
                            + " source=" + resultSet.getString("source")
                            + " lines=\"" + dash(resultSet.getString("line_labels")) + "\"");
                    System.out.println("  explanation=" + dash(resultSet.getString("explanation")));
                }
            }
        }
    }

    private static void printMembers(Connection connection, String canonicalAreaId, Options options) throws SQLException {
        System.out.println("  members:");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT member.area_id,
                       area.area_name,
                       member.member_role,
                       member.display_role,
                       member.is_primary_for_search,
                       member.is_primary_for_routing,
                       member.is_visible_suggestion,
                       member.access_cost_minutes,
                       member.quality,
                       member.distance_meters,
                       member.source,
                       member.explanation,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.has_train, 0) AS has_train,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.search_priority_score, 0) AS search_priority_score,
                       COALESCE(profile.line_labels, '') AS line_labels
                FROM canonical_stop_area_members member
                JOIN stop_areas area ON area.area_id = member.area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = member.area_id
                WHERE member.canonical_area_id = ?
                ORDER BY member.is_primary_for_search DESC,
                         member.is_primary_for_routing DESC,
                         member.is_visible_suggestion DESC,
                         member.access_cost_minutes,
                         member.member_role,
                         area.area_name
                """)) {
            statement.setString(1, canonicalAreaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String areaId = resultSet.getString("area_id");
                    long departures = activeDepartures(connection, areaId, options);
                    System.out.println("    - area=" + areaId
                            + " name=\"" + resultSet.getString("area_name") + "\""
                            + " role=" + resultSet.getString("member_role")
                            + " displayRole=" + resultSet.getString("display_role")
                            + " search=" + bool(resultSet, "is_primary_for_search")
                            + " routing=" + bool(resultSet, "is_primary_for_routing")
                            + " visible=" + bool(resultSet, "is_visible_suggestion")
                            + " access=" + resultSet.getInt("access_cost_minutes")
                            + " quality=" + resultSet.getString("quality")
                            + " distance=" + nullableInt(resultSet, "distance_meters")
                            + " profile=" + resultSet.getString("profile_class")
                            + " rail=" + bool(resultSet, "has_rail_service")
                            + " train=" + bool(resultSet, "has_train")
                            + " busOnly=" + bool(resultSet, "bus_only")
                            + " score=" + resultSet.getInt("search_priority_score")
                            + " departures=" + departures
                            + " lines=\"" + dash(resultSet.getString("line_labels")) + "\"");
                    System.out.println("      source=" + resultSet.getString("source")
                            + " explanation=" + dash(resultSet.getString("explanation")));
                }
            }
        }
    }

    private static long activeDepartures(Connection connection, String areaId, Options options) throws SQLException {
        String sql = activeServicesCte(options.date()) + """
                SELECT COUNT(*) AS active_departures
                FROM stop_area_members member
                JOIN stop_times time ON time.stop_id = member.stop_id
                JOIN trips trip ON trip.trip_id = time.trip_id
                JOIN active_services active ON active.service_id = trip.service_id
                WHERE member.area_id = ?
                  AND time.departure_seconds >= ?
                  AND time.departure_seconds <= ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = bindActiveServiceParameters(statement, options.date());
            statement.setString(index++, areaId);
            statement.setInt(index++, toSeconds(options.from()));
            statement.setInt(index, toSeconds(options.to()));
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("active_departures") : 0;
            }
        }
    }

    private static void printInternalEdges(Connection connection, String canonicalAreaId) throws SQLException {
        System.out.println("  internalTransferEdges:");
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT from_area_id,
                       to_area_id,
                       distance_meters,
                       min_transfer_minutes,
                       quality,
                       source,
                       explanation
                FROM canonical_stop_area_transfer_edges
                WHERE canonical_area_id = ?
                ORDER BY from_area_id, to_area_id
                LIMIT 24
                """)) {
            statement.setString(1, canonicalAreaId);
            int count = 0;
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    count++;
                    System.out.println("    - " + resultSet.getString("from_area_id")
                            + " -> " + resultSet.getString("to_area_id")
                            + " transfer=" + resultSet.getInt("min_transfer_minutes")
                            + " quality=" + resultSet.getString("quality")
                            + " distance=" + nullableInt(resultSet, "distance_meters")
                            + " source=" + resultSet.getString("source")
                            + " explanation=" + dash(resultSet.getString("explanation")));
                }
            }
            if (count == 0) {
                System.out.println("    none");
            }
        }
    }

    private static String activeServicesCte(LocalDate date) {
        String weekdayColumn = weekdayColumn(date.getDayOfWeek());
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
                active_services AS (
                    SELECT service_id FROM calendar_active
                    WHERE service_id NOT IN (SELECT service_id FROM service_removals)
                    UNION
                    SELECT service_id FROM service_additions
                )
                """.formatted(weekdayColumn);
    }

    private static int bindActiveServiceParameters(PreparedStatement statement, LocalDate date) throws SQLException {
        String gtfsDate = GTFS_DATE_FORMAT.format(date);
        statement.setString(1, gtfsDate);
        statement.setString(2, gtfsDate);
        statement.setString(3, gtfsDate);
        statement.setString(4, gtfsDate);
        return 5;
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

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static List<String> queryTokens(String normalized) {
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split(" ")) {
            String trimmed = token.trim();
            if (!trimmed.isBlank()) {
                tokens.add(trimmed);
            }
        }
        return tokens;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static int toSeconds(LocalTime time) {
        return time.toSecondOfDay();
    }

    private static boolean bool(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getInt(column) != 0;
    }

    private static String nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? "-" : Integer.toString(value);
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private record AreaHeader(
            String areaId,
            String name,
            String profileClass,
            boolean hasRailService,
            boolean busOnly,
            String lineLabels
    ) {
    }

    private record SearchHit(
            String areaId,
            String name,
            int score,
            String profileClass,
            boolean hasRailService,
            boolean busOnly,
            String lineLabels,
            List<String> signals
    ) {
    }

    private static final class MutableSearchHit {
        private final String areaId;
        private final String name;
        private final String profileClass;
        private final boolean hasRailService;
        private final boolean busOnly;
        private final String lineLabels;
        private final List<String> signals = new ArrayList<>();
        private int score;

        private MutableSearchHit(
                String areaId,
                String name,
                String profileClass,
                boolean hasRailService,
                boolean busOnly,
                String lineLabels
        ) {
            this.areaId = areaId;
            this.name = name;
            this.profileClass = profileClass;
            this.hasRailService = hasRailService;
            this.busOnly = busOnly;
            this.lineLabels = lineLabels;
        }

        private SearchHit toHit() {
            return new SearchHit(areaId, name, score, profileClass, hasRailService, busOnly, lineLabels, List.copyOf(signals));
        }
    }

    private record Options(
            Path database,
            List<String> queries,
            List<String> areaIds,
            LocalDate date,
            LocalTime from,
            LocalTime to,
            int top
    ) {
        private static Options parse(String[] args) {
            Path database = null;
            List<String> queries = new ArrayList<>();
            List<String> areaIds = new ArrayList<>();
            LocalDate date = LocalDate.of(2026, 6, 30);
            LocalTime from = LocalTime.of(6, 7);
            LocalTime to = LocalTime.of(8, 7);
            int top = DEFAULT_TOP;

            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                switch (arg) {
                    case "--database", "--db" -> database = Path.of(requireValue(args, ++index, arg));
                    case "--query" -> queries.add(requireValue(args, ++index, arg));
                    case "--area-id" -> areaIds.add(requireValue(args, ++index, arg));
                    case "--date" -> date = LocalDate.parse(requireValue(args, ++index, arg));
                    case "--from" -> from = LocalTime.parse(requireValue(args, ++index, arg));
                    case "--to" -> to = LocalTime.parse(requireValue(args, ++index, arg));
                    case "--top" -> top = Integer.parseInt(requireValue(args, ++index, arg));
                    default -> throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            if (database == null) {
                throw new IllegalArgumentException("Missing --database");
            }
            return new Options(database, List.copyOf(queries), List.copyOf(areaIds), date, from, to, top);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }
}
