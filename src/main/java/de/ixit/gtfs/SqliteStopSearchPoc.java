package de.ixit.gtfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SqliteStopSearchPoc {
    private static final int DEFAULT_TOP_N = 5;
    private static final int MAX_TOP_N = 50;
    private static final int PER_TOKEN_LIMIT = 8_000;
    private static final List<String> DEFAULT_QUERIES = List.of(
            "Dortmund Hbf",
            "Dortmund",
            "Castrop",
            "Castrop-Rauxel",
            "Gelsenkirchen",
            "Berlin Hauptbahnhof",
            "Regensburg Hbf",
            "Lingen Bahnhof",
            "Neumarkt",
            "Dortm Hbf"
    );

    private SqliteStopSearchPoc() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("SQLite StopSearch PoC failed: " + ex.getMessage());
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
            if (options.printSchema()) {
                printSchema(connection);
            }
            if (options.benchmark()) {
                runBenchmark(connection, options.topN());
            } else {
                SearchRun run = search(connection, options.query(), options.topN());
                printSearchRun(run, options.topN());
            }
        }
    }

    private static void configureReadOnlyConnection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    private static void printSchema(Connection connection) throws SQLException {
        System.out.println("SQLite StopSearch schema/index check");
        printTableInfo(connection, "stop_search_tokens");
        printTableInfo(connection, "stop_areas");
        printTableInfo(connection, "stop_area_members");
        printTableInfo(connection, "stops");
        printIndexes(connection, "stop_search_tokens");
        printIndexes(connection, "stop_areas");
        printIndexes(connection, "stop_area_members");
        printIndexes(connection, "stops");
        System.out.println();
    }

    private static void printTableInfo(Connection connection, String tableName) throws SQLException {
        System.out.println("TABLE " + tableName);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                System.out.println("- " + resultSet.getString("name") + " " + resultSet.getString("type"));
            }
        }
    }

    private static void printIndexes(Connection connection, String tableName) throws SQLException {
        System.out.println("INDEXES " + tableName);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA index_list(" + tableName + ")")) {
            while (resultSet.next()) {
                System.out.println("- " + resultSet.getString("name"));
            }
        }
    }

    private static void runBenchmark(Connection connection, int topN) throws SQLException {
        System.out.println("SQLite StopSearch benchmark");
        System.out.println("strategy=token exact + token prefix range, grouped by area_id, topN=" + topN);
        System.out.println();
        System.out.println("| Query | Cold-ish ms | Warm ms | Candidates | Top result | Top score |");
        System.out.println("| --- | ---: | ---: | ---: | --- | ---: |");
        List<SearchRun> warmRuns = new ArrayList<>();
        for (String query : DEFAULT_QUERIES) {
            SearchRun cold = search(connection, query, topN);
            SearchRun warm = search(connection, query, topN);
            warmRuns.add(warm);
            SearchHit top = warm.hits().isEmpty() ? null : warm.hits().get(0);
            System.out.println("| " + query + " | "
                    + cold.durationMs() + " | "
                    + warm.durationMs() + " | "
                    + warm.candidateAreas() + " | "
                    + (top == null ? "-" : escapeTable(top.displayName())) + " | "
                    + (top == null ? 0 : top.score()) + " |");
        }
        System.out.println();
        for (SearchRun run : warmRuns) {
            printSearchRun(run, topN);
        }
    }

    private static SearchRun search(Connection connection, String rawQuery, int topN) throws SQLException {
        long startedNanos = System.nanoTime();
        String normalized = StopNameNormalizer.normalize(rawQuery);
        List<String> primaryTokens = primaryTokens(normalized);
        List<String> queryTokens = queryTokens(primaryTokens);
        Map<String, MutableHit> hits = new LinkedHashMap<>();

        if (!normalized.isBlank()) {
            collectTokenMatches(connection, hits, normalized, normalized, true, false);
        }
        boolean hasSpecificPrimaryToken = primaryTokens.stream().anyMatch(token -> !isGenericStationToken(token));
        for (String queryToken : queryTokens) {
            if (primaryTokens.size() > 1 && hasSpecificPrimaryToken && isGenericStationToken(queryToken)) {
                continue;
            }
            collectTokenMatches(connection, hits, queryToken, queryToken, true, false);
            if (queryToken.length() >= 3) {
                collectTokenMatches(connection, hits, queryToken, queryToken, false, true);
            }
        }

        for (MutableHit hit : hits.values()) {
            applyNameScore(hit, normalized, primaryTokens);
        }

        List<SearchHit> sorted = hits.values().stream()
                .map(MutableHit::toHit)
                .sorted(Comparator
                        .comparingInt(SearchHit::score).reversed()
                        .thenComparing(SearchHit::displayName)
                        .thenComparing(SearchHit::areaId))
                .limit(topN)
                .toList();

        long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        return new SearchRun(rawQuery, normalized, queryTokens, durationMs, hits.size(), sorted);
    }

    private static void collectTokenMatches(
            Connection connection,
            Map<String, MutableHit> hits,
            String queryToken,
            String tokenValue,
            boolean exact,
            boolean prefix
    ) throws SQLException {
        String sql;
        if (exact) {
            sql = """
                    SELECT sst.area_id, sst.token, sst.token_type, sst.source,
                           sa.area_name, sa.area_name_normalized, sa.area_lat, sa.area_lon, sa.stop_count
                    FROM stop_search_tokens sst
                    JOIN stop_areas sa ON sa.area_id = sst.area_id
                    WHERE sst.token = ?
                    LIMIT ?
                    """;
        } else {
            sql = """
                    SELECT sst.area_id, sst.token, sst.token_type, sst.source,
                           sa.area_name, sa.area_name_normalized, sa.area_lat, sa.area_lon, sa.stop_count
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
                statement.setInt(2, PER_TOKEN_LIMIT);
            } else {
                statement.setString(2, nextPrefix(tokenValue));
                statement.setInt(3, PER_TOKEN_LIMIT);
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String areaId = resultSet.getString("area_id");
                    MutableHit hit = hits.computeIfAbsent(areaId, ignored -> new MutableHit(
                            areaId,
                            resultSetString(resultSet, "area_name"),
                            resultSetString(resultSet, "area_name_normalized"),
                            resultSetDouble(resultSet, "area_lat"),
                            resultSetDouble(resultSet, "area_lon"),
                            resultSetInt(resultSet, "stop_count")
                    ));
                    String matchedToken = resultSet.getString("token");
                    hit.matchedTokens.add(matchedToken);
                    hit.matchedQueryTokens.add(queryToken);
                    if (exact) {
                        if (hit.scoredExactQueryTokens.add(queryToken)) {
                            hit.score += "NORMALIZED".equals(resultSet.getString("token_type")) ? 1_600 : 1_000;
                        }
                    } else {
                        if (hit.scoredPrefixQueryTokens.add(queryToken)) {
                            hit.score += 420;
                        }
                    }
                    if ("AREA_NAME".equals(resultSet.getString("source")) && hit.areaNameBonusSources.add(queryToken + ":" + matchedToken)) {
                        hit.score += 180;
                    }
                }
            }
        }
    }

    private static void applyNameScore(MutableHit hit, String normalizedQuery, List<String> primaryTokens) {
        if (!normalizedQuery.isBlank() && normalizedQuery.equals(hit.normalizedName)) {
            hit.score += 8_000;
        } else if (!normalizedQuery.isBlank() && hit.normalizedName.startsWith(normalizedQuery)) {
            hit.score += 3_000;
        } else if (!normalizedQuery.isBlank() && hit.normalizedName.contains(normalizedQuery)) {
            hit.score += 1_200;
        }

        int missingPrimaryTokens = 0;
        Set<String> normalizedNameTokens = new LinkedHashSet<>(List.of(hit.normalizedName.split(" ")));
        for (String primaryToken : primaryTokens) {
            if (!hit.matchedQueryTokens.contains(primaryToken) && !normalizedNameTokens.contains(primaryToken)) {
                missingPrimaryTokens++;
            }
        }
        if (!primaryTokens.isEmpty() && missingPrimaryTokens == 0) {
            hit.score += 4_000;
        } else {
            hit.score -= missingPrimaryTokens * 4_000;
        }
        hit.score += Math.min(120, Math.max(0, hit.stopCount) * 4);
    }

    private static List<String> primaryTokens(String normalized) {
        if (normalized.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (!token.isBlank()) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static List<String> queryTokens(List<String> primaryTokens) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : primaryTokens) {
            if (!token.isBlank()) {
                tokens.add(token);
                switch (token) {
                    case "hbf" -> tokens.add("hauptbahnhof");
                    case "hauptbahnhof" -> tokens.add("hbf");
                    case "bf" -> tokens.add("bahnhof");
                    case "bahnhof" -> tokens.add("bf");
                    case "u", "ubahn" -> tokens.add("ubahn");
                    case "s", "sbahn" -> tokens.add("sbahn");
                    default -> {
                    }
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static String nextPrefix(String value) {
        if (value.isEmpty()) {
            return "\uFFFF";
        }
        char[] chars = value.toCharArray();
        chars[chars.length - 1]++;
        return new String(chars);
    }

    private static boolean isGenericStationToken(String token) {
        return switch (token) {
            case "hbf", "hauptbahnhof", "bf", "bahnhof", "u", "ubahn", "s", "sbahn" -> true;
            default -> false;
        };
    }

    private static void printSearchRun(SearchRun run, int topN) {
        System.out.println("Query: " + run.rawQuery()
                + " | normalized=" + run.normalizedQuery()
                + " | tokens=" + run.queryTokens()
                + " | durationMs=" + run.durationMs()
                + " | candidateAreas=" + run.candidateAreas());
        int rank = 1;
        for (SearchHit hit : run.hits()) {
            System.out.println(rank + ". " + hit.displayName()
                    + " | area_id=" + hit.areaId()
                    + " | score=" + hit.score()
                    + " | stop_count=" + hit.stopCount()
                    + " | lat=" + formatNullable(hit.lat())
                    + " | lon=" + formatNullable(hit.lon())
                    + " | matched=" + hit.matchedTokens());
            rank++;
        }
        if (run.hits().isEmpty()) {
            System.out.println("(no hits)");
        }
        System.out.println("topN=" + topN);
        System.out.println();
    }

    private static String resultSetString(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Double resultSetDouble(ResultSet resultSet, String column) {
        try {
            double value = resultSet.getDouble(column);
            return resultSet.wasNull() ? null : value;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static int resultSetInt(ResultSet resultSet, String column) {
        try {
            return resultSet.getInt(column);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String formatNullable(Double value) {
        return value == null ? "-" : String.format(Locale.ROOT, "%.5f", value);
    }

    private static String escapeTable(String value) {
        return value == null ? "" : value.replace("|", "\\|");
    }

    private record SearchRun(
            String rawQuery,
            String normalizedQuery,
            List<String> queryTokens,
            long durationMs,
            int candidateAreas,
            List<SearchHit> hits
    ) {
    }

    private record SearchHit(
            String areaId,
            String displayName,
            int score,
            int stopCount,
            Double lat,
            Double lon,
            List<String> matchedTokens
    ) {
    }

    private static final class MutableHit {
        private final String areaId;
        private final String displayName;
        private final String normalizedName;
        private final Double lat;
        private final Double lon;
        private final int stopCount;
        private final Set<String> matchedTokens = new LinkedHashSet<>();
        private final Set<String> matchedQueryTokens = new LinkedHashSet<>();
        private final Set<String> scoredExactQueryTokens = new LinkedHashSet<>();
        private final Set<String> scoredPrefixQueryTokens = new LinkedHashSet<>();
        private final Set<String> areaNameBonusSources = new LinkedHashSet<>();
        private int score;

        private MutableHit(String areaId, String displayName, String normalizedName, Double lat, Double lon, int stopCount) {
            this.areaId = areaId;
            this.displayName = displayName;
            this.normalizedName = normalizedName == null ? "" : normalizedName;
            this.lat = lat;
            this.lon = lon;
            this.stopCount = stopCount;
        }

        private SearchHit toHit() {
            return new SearchHit(areaId, displayName, score, stopCount, lat, lon, List.copyOf(matchedTokens));
        }
    }

    private record Options(Path database, String query, int topN, boolean benchmark, boolean printSchema) {
        private static Options parse(String[] args) {
            Path database = Path.of("build", "gtfs-de-full-core-v0_5.sqlite");
            String query = null;
            int topN = DEFAULT_TOP_N;
            boolean benchmark = false;
            boolean printSchema = false;

            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                switch (arg) {
                    case "--db" -> database = Path.of(requireValue(args, ++index, arg));
                    case "--query" -> query = requireValue(args, ++index, arg);
                    case "--top" -> topN = Integer.parseInt(requireValue(args, ++index, arg));
                    case "--benchmark" -> benchmark = true;
                    case "--schema" -> printSchema = true;
                    case "--help", "-h" -> throw new IllegalArgumentException(usage());
                    default -> {
                        if (arg.startsWith("-")) {
                            throw new IllegalArgumentException("Unknown option: " + arg + System.lineSeparator() + usage());
                        }
                        query = arg;
                    }
                }
            }

            if (!benchmark && (query == null || query.isBlank())) {
                throw new IllegalArgumentException("Missing --query or --benchmark." + System.lineSeparator() + usage());
            }
            return new Options(database, query, Math.min(MAX_TOP_N, Math.max(1, topN)), benchmark, printSchema);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static String usage() {
            return """
                    Usage:
                      mvn -q package
                      java --enable-native-access=ALL-UNNAMED -cp target/gtfs-preprocessor-0.9.8.jar de.ixit.gtfs.SqliteStopSearchPoc --db build/gtfs-de-full-core-v0_6.sqlite --query "Dortmund Hbf" --top 5
                      java --enable-native-access=ALL-UNNAMED -cp target/gtfs-preprocessor-0.9.8.jar de.ixit.gtfs.SqliteStopSearchPoc --db build/gtfs-de-full-core-v0_6.sqlite --benchmark --schema --top 5
                    """;
        }
    }
}
