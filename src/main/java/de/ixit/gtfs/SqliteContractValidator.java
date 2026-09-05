package de.ixit.gtfs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SqliteContractValidator {
    private SqliteContractValidator() {
    }

    public static SqliteContractReport validate(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            Set<String> tables = readNames(connection, "table");
            Set<String> indexes = readNames(connection, "index");
            Map<String, String> metadata = readMetadata(connection, tables);
            List<String> errors = new ArrayList<>();

            for (String table : SqliteContract.EXPECTED_TABLES) {
                if (!tables.contains(table)) {
                    errors.add("Missing expected table: " + table);
                }
            }

            if (!tables.contains("ixit_metadata")) {
                errors.add("Missing ixit_metadata table.");
            } else {
                requireMetadata(metadata, "schema_version", SqliteContract.SCHEMA_VERSION, errors);
                requireMetadataOneOf(metadata, "contract_version", SqliteContract.SUPPORTED_CONTRACT_VERSIONS, errors);
                requireMetadata(metadata, "preprocessor_version", SqliteContract.PREPROCESSOR_VERSION, errors);
                requireMetadata(metadata, "contract_name", SqliteContract.CONTRACT_NAME, errors);
                requireMetadata(metadata, "time_model", SqliteContract.TIME_MODEL, errors);
                requireMetadata(metadata, "stop_id_policy", SqliteContract.STOP_ID_POLICY, errors);
                requireMetadata(metadata, "area_id_policy", SqliteContract.AREA_ID_POLICY, errors);
                requireMetadata(metadata, "search_tokens_policy", SqliteContract.SEARCH_TOKENS_POLICY, errors);
                requireMetadata(metadata, "display_name_transformation_version",
                        SqliteContract.DISPLAY_NAME_TRANSFORMATION_VERSION, errors);
                requireMetadata(metadata, "display_name_transformation_policy",
                        SqliteContract.DISPLAY_NAME_TRANSFORMATION_POLICY, errors);
                requireMetadata(metadata, "service_day_model_version", ServiceDayModelAuditor.MODEL_VERSION, errors);
                requireMetadata(metadata, "service_day_resolution_policy", SqliteContract.SERVICE_DAY_RESOLUTION_POLICY, errors);
                requireMetadata(metadata, "service_day_timezone_policy", SqliteContract.SERVICE_DAY_TIMEZONE_POLICY, errors);
                requireMetadata(metadata, "service_day_time_overflow_policy", SqliteContract.SERVICE_DAY_TIME_OVERFLOW_POLICY, errors);
                requireMetadata(metadata, "transfer_semantics_policy", SqliteContract.TRANSFER_SEMANTICS_POLICY, errors);
                requireMetadata(metadata, "footpath_policy", SqliteContract.FOOTPATH_POLICY, errors);
                requireMetadata(metadata, "walk_model_version", SqliteContract.WALK_MODEL_VERSION, errors);
                requireMetadataPresent(metadata, "feed_timezones", errors);
                requireMetadata(metadata, "build_identity_version", BuildIdentity.IDENTITY_VERSION, errors);
                requireSha256Metadata(metadata, "build_identity_sha256", errors);
                requireSha256Metadata(metadata, "source_gtfs_sha256", errors);
                requireSha256Metadata(metadata, "preprocessor_artifact_sha256", errors);
                requireMetadataPresent(metadata, "preprocessor_artifact_kind", errors);
                requireMetadataPresent(metadata, "municipality_data_sha256", errors);
                requireMetadataOneOf(metadata, "run_mode", List.of("FULL", "CORE_ONLY", "APP_RUNTIME"), errors);
            }
            boolean appRuntimeMode = "APP_RUNTIME".equals(metadata.get("run_mode"));
            if (appRuntimeMode) {
                for (String table : SqliteContract.ADDITIVE_TABLES) {
                    if (!tables.contains(table)) {
                        errors.add("Missing app-runtime table for contract "
                                + SqliteContract.APP_RUNTIME_CONTRACT_VERSION
                                + ": "
                                + table);
                    }
                }
            }

            List<String> expectedIndexes = SqliteContract.expectedIndexesFor(
                    metadata.get("contract_version"),
                    metadata.get("run_mode")
            );
            List<String> additiveIndexes = SqliteContract.additiveIndexesFor(
                    metadata.get("contract_version"),
                    metadata.get("run_mode")
            );

            for (String index : expectedIndexes) {
                if (!indexes.contains(index)) {
                    errors.add("Missing expected index: " + index);
                }
            }
            if (appRuntimeMode || tables.contains("transfer_edges")) {
                for (String index : additiveIndexes) {
                    if (!indexes.contains(index)) {
                        errors.add("Missing additive index: " + index);
                    }
                }
            }

            for (Map.Entry<String, List<String>> requiredColumns : SqliteContract.REQUIRED_COLUMNS.entrySet()) {
                if (tables.contains(requiredColumns.getKey())) {
                    Set<String> columns = readColumns(connection, requiredColumns.getKey());
                    for (String column : requiredColumns.getValue()) {
                        if (!columns.contains(column)) {
                            errors.add("Missing expected column: " + requiredColumns.getKey() + "." + column);
                        }
                    }
                }
            }

            if (errors.isEmpty()) {
                TransferFootpathAuditReport transferAudit = TransferFootpathAuditor.audit(connection);
                if (!transferAudit.pass()) {
                    errors.add("Transfer/footpath semantic audit failed: non_pedestrian_gtfs_edges="
                            + transferAudit.nonPedestrianGtfsEdges()
                            + ", scoped_gtfs_edges=" + transferAudit.scopedGtfsEdges()
                            + ", traversable_heuristics=" + transferAudit.traversableHeuristicEdges()
                            + ", traversable_area_membership=" + transferAudit.traversableAreaMembershipEdges()
                            + ", over_distance_footpaths=" + transferAudit.overDistanceTraversableFootpaths()
                            + ", zero_time_footpaths=" + transferAudit.zeroTimeTraversableFootpaths()
                            + ", areas_without_footpaths=" + transferAudit.areasWithoutFootpathRows()
                            + ", invalid_walk_components=" + transferAudit.invalidWalkComponents()
                            + ", prohibited_walks=" + transferAudit.prohibitedWalks());
                }
            }

            if (!errors.isEmpty()) {
                throw new IllegalStateException("SQLite contract validation failed: " + String.join("; ", errors));
            }

            List<String> reportedTables = reportedNames(SqliteContract.EXPECTED_TABLES, SqliteContract.ADDITIVE_TABLES, tables);
            List<String> reportedIndexes = reportedNames(expectedIndexes, additiveIndexes, indexes);
            return new SqliteContractReport(
                    metadata.get("schema_version"),
                    metadata.get("preprocessor_version"),
                    metadata.get("contract_name"),
                    metadata.get("contract_version"),
                    Map.copyOf(metadata),
                    reportedTables,
                    reportedIndexes,
                    readRowCounts(connection, reportedTables),
                    readHubProfileStats(connection, tables),
                    readRouteAxisStats(connection, tables),
                    readTransferRuleStats(connection, tables),
                    readTransferEdgeStats(connection, tables),
                    SqliteContract.TIME_MODEL,
                    SqliteContract.STOP_ID_POLICY,
                    SqliteContract.AREA_ID_POLICY,
                    SqliteContract.SEARCH_TOKENS_POLICY
            );
        }
    }

    private static Set<String> readNames(Connection connection, String type) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (var statement = connection.prepareStatement("SELECT name FROM sqlite_master WHERE type = ?")) {
            statement.setString(1, type);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
            }
        }
        return names;
    }

    private static List<String> reportedNames(List<String> expectedNames, List<String> additiveNames, Set<String> actualNames) {
        List<String> reported = new ArrayList<>(expectedNames);
        for (String additiveName : additiveNames) {
            if (actualNames.contains(additiveName)) {
                reported.add(additiveName);
            }
        }
        return List.copyOf(reported);
    }

    private static Map<String, String> readMetadata(Connection connection, Set<String> tables) throws SQLException {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (!tables.contains("ixit_metadata")) {
            return metadata;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT key, value FROM ixit_metadata")) {
            while (resultSet.next()) {
                metadata.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        return metadata;
    }

    private static Set<String> readColumns(Connection connection, String tableName) throws SQLException {
        Set<String> columns = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private static Map<String, Long> readRowCounts(Connection connection, List<String> tableNames) throws SQLException {
        Map<String, Long> rowCounts = new LinkedHashMap<>();
        for (String tableName : tableNames) {
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                resultSet.next();
                rowCounts.put(tableName, resultSet.getLong(1));
            }
        }
        return rowCounts;
    }

    private static HubProfileBuilder.HubProfileStats readHubProfileStats(Connection connection, Set<String> tables) throws SQLException {
        if (!tables.contains("hub_profiles")) {
            return null;
        }
        List<de.ixit.gtfs.model.HubProfile> profiles = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT area_id,
                            hub_level,
                            stop_count,
                            route_count,
                            trip_count,
                            route_type_count,
                            stop_time_count,
                            has_train,
                            has_subway,
                            has_tram,
                            has_bus,
                            has_rail_keyword,
                            has_main_station_keyword,
                            transfer_candidate_score,
                            explanation
                     FROM hub_profiles
                     """)) {
            while (resultSet.next()) {
                profiles.add(new de.ixit.gtfs.model.HubProfile(
                        resultSet.getString("area_id"),
                        resultSet.getString("hub_level"),
                        resultSet.getInt("stop_count"),
                        resultSet.getInt("route_count"),
                        resultSet.getInt("trip_count"),
                        resultSet.getInt("route_type_count"),
                        resultSet.getInt("stop_time_count"),
                        resultSet.getInt("has_train") == 1,
                        resultSet.getInt("has_subway") == 1,
                        resultSet.getInt("has_tram") == 1,
                        resultSet.getInt("has_bus") == 1,
                        resultSet.getInt("has_rail_keyword") == 1,
                        resultSet.getInt("has_main_station_keyword") == 1,
                        resultSet.getInt("transfer_candidate_score"),
                        resultSet.getString("explanation")
                ));
            }
        }
        return HubProfileBuilder.HubProfileStats.from(profiles);
    }

    private static RouteAxisBuilder.RouteAxisStats readRouteAxisStats(Connection connection, Set<String> tables) throws SQLException {
        if (!tables.contains("route_axes") || !tables.contains("route_axis_stops")) {
            return null;
        }
        List<de.ixit.gtfs.model.RouteAxis> axes = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT axis_id,
                            route_id,
                            direction_id,
                            representative_trip_id,
                            trip_count,
                            stop_count,
                            first_area_id,
                            last_area_id,
                            route_short_name,
                            route_long_name,
                            route_type,
                            explanation
                     FROM route_axes
                     """)) {
            while (resultSet.next()) {
                axes.add(new de.ixit.gtfs.model.RouteAxis(
                        resultSet.getString("axis_id"),
                        resultSet.getString("route_id"),
                        resultSet.getString("direction_id"),
                        resultSet.getString("representative_trip_id"),
                        resultSet.getInt("trip_count"),
                        resultSet.getInt("stop_count"),
                        resultSet.getString("first_area_id"),
                        resultSet.getString("last_area_id"),
                        resultSet.getString("route_short_name"),
                        resultSet.getString("route_long_name"),
                        (Integer) resultSet.getObject("route_type"),
                        resultSet.getString("explanation")
                ));
            }
        }

        List<de.ixit.gtfs.model.RouteAxisStop> axisStops = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT axis_id, sequence_index, area_id FROM route_axis_stops")) {
            while (resultSet.next()) {
                axisStops.add(new de.ixit.gtfs.model.RouteAxisStop(
                        resultSet.getString("axis_id"),
                        resultSet.getInt("sequence_index"),
                        resultSet.getString("area_id")
                ));
            }
        }
        return RouteAxisBuilder.RouteAxisStats.from(axes, axisStops, Set.of(), 0, 0, 0, Set.of());
    }

    private static TransferRuleBuilder.TransferRuleStats readTransferRuleStats(Connection connection, Set<String> tables) throws SQLException {
        if (!tables.contains("transfer_rules")) {
            return null;
        }
        Map<String, Integer> sourceCounts = initializedCounts(
                "GTFS_TRANSFERS",
                "SAME_STOP_AREA",
                "GENERATED_PLATFORM_TRANSFER",
                "GENERATED_NEARBY_AREA"
        );
        mergeGroupedCounts(connection, "transfer_rules", "source", sourceCounts);
        Map<String, Integer> confidenceCounts = initializedCounts("HIGH", "MEDIUM", "LOW");
        mergeGroupedCounts(connection, "transfer_rules", "confidence", confidenceCounts);
        int ruleCount = readCount(connection, "transfer_rules");
        int gtfsMapped = sourceCounts.getOrDefault("GTFS_TRANSFERS", 0);
        int sameArea = sourceCounts.getOrDefault("SAME_STOP_AREA", 0);
        Map<String, Integer> semanticCounts = new LinkedHashMap<>();
        mergeGroupedCounts(connection, "transfer_rules", "transfer_semantic", semanticCounts);
        int scopedTransfers = countWhere(connection, "transfer_rules", "raw_transfer_id IS NOT NULL AND scope_type <> 'STOP'");
        int pedestrianTransfers = countWhere(connection, "transfer_rules", "raw_transfer_id IS NOT NULL AND pedestrian_usable=1");
        return new TransferRuleBuilder.TransferRuleStats(
                ruleCount,
                sourceCounts,
                confidenceCounts,
                gtfsMapped,
                gtfsMapped,
                0,
                sameArea,
                scopedTransfers,
                pedestrianTransfers,
                Math.max(0, gtfsMapped - pedestrianTransfers),
                semanticCounts,
                List.of(),
                List.of()
        );
    }

    private static TransferEdgeBuilder.TransferEdgeStats readTransferEdgeStats(Connection connection, Set<String> tables) throws SQLException {
        if (!tables.contains("transfer_edges")) {
            return null;
        }
        Map<String, Integer> sourceCounts = initializedCounts(
                "GTFS_TRANSFERS",
                "SAME_STOP_AREA",
                "DISTANCE_HEURISTIC",
                "MANUAL_FUTURE"
        );
        mergeGroupedCounts(connection, "transfer_edges", "source", sourceCounts);
        Map<String, Integer> qualityCounts = initializedCounts("GOOD", "OK", "LONG", "AVOID");
        mergeGroupedCounts(connection, "transfer_edges", "quality", qualityCounts);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COUNT(*) AS edge_count,
                            MIN(distance_meters) AS min_distance_meters,
                            MAX(distance_meters) AS max_distance_meters,
                            AVG(distance_meters) AS average_distance_meters,
                            MIN(min_transfer_minutes) AS min_transfer_minutes,
                            MAX(min_transfer_minutes) AS max_transfer_minutes,
                            AVG(min_transfer_minutes) AS average_transfer_minutes
                     FROM transfer_edges
                     """)) {
            int edgeCount = resultSet.getInt("edge_count");
            int traversableCount = countWhere(connection, "transfer_edges", "is_traversable=1");
            return new TransferEdgeBuilder.TransferEdgeStats(
                    edgeCount,
                    sourceCounts,
                    qualityCounts,
                    nullableInteger(resultSet, "min_distance_meters"),
                    nullableInteger(resultSet, "max_distance_meters"),
                    resultSet.getDouble("average_distance_meters"),
                    edgeCount == 0 ? 0 : resultSet.getInt("min_transfer_minutes"),
                    edgeCount == 0 ? 0 : resultSet.getInt("max_transfer_minutes"),
                    resultSet.getDouble("average_transfer_minutes"),
                    traversableCount,
                    Math.max(0, edgeCount - traversableCount)
            );
        }
    }

    private static Map<String, Integer> initializedCounts(String... values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String value : values) {
            counts.put(value, 0);
        }
        return counts;
    }

    private static void mergeGroupedCounts(
            Connection connection,
            String table,
            String column,
            Map<String, Integer> counts
    ) throws SQLException {
        String sql = "SELECT " + column + ", COUNT(*) FROM " + table + " GROUP BY " + column;
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                counts.put(resultSet.getString(1), resultSet.getInt(2));
            }
        }
    }

    private static int readCount(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return resultSet.getInt(1);
        }
    }

    private static int countWhere(Connection connection, String table, String condition) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + condition)) {
            return resultSet.getInt(1);
        }
    }

    private static Integer nullableInteger(ResultSet resultSet, String column) throws SQLException {
        Object value = resultSet.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }

    private static void requireMetadata(Map<String, String> metadata, String key, String expectedValue, List<String> errors) {
        String actual = metadata.get(key);
        if (actual == null || actual.isBlank()) {
            errors.add("Missing ixit_metadata value: " + key);
        } else if (!expectedValue.equals(actual)) {
            errors.add("Unexpected ixit_metadata value for " + key + ": expected " + expectedValue + ", got " + actual);
        }
    }

    private static void requireMetadataOneOf(Map<String, String> metadata, String key, List<String> expectedValues, List<String> errors) {
        String actual = metadata.get(key);
        if (actual == null || actual.isBlank()) {
            errors.add("Missing ixit_metadata value: " + key);
        } else if (!expectedValues.contains(actual)) {
            errors.add("Unexpected ixit_metadata value for " + key + ": expected one of " + expectedValues + ", got " + actual);
        }
    }

    private static void requireMetadataPresent(Map<String, String> metadata, String key, List<String> errors) {
        String actual = metadata.get(key);
        if (actual == null || actual.isBlank()) {
            errors.add("Missing ixit_metadata value: " + key);
        }
    }

    private static void requireSha256Metadata(Map<String, String> metadata, String key, List<String> errors) {
        String actual = metadata.get(key);
        if (actual == null || !actual.matches("[0-9a-f]{64}")) {
            errors.add("Invalid SHA-256 ixit_metadata value: " + key);
        }
    }
}
