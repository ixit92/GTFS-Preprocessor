package de.ixit.gtfs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TransferFootpathAuditor {
    public static final String AUDIT_VERSION = "0.1";

    private TransferFootpathAuditor() {
    }

    public static TransferFootpathAuditReport audit(Path database) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            return audit(connection);
        }
    }

    static TransferFootpathAuditReport audit(Connection connection) throws SQLException {
        String runMode = readText(connection, "SELECT value FROM ixit_metadata WHERE key='run_mode'");
        boolean available = !"CORE_ONLY".equals(runMode);
        long rawTransfers = count(connection, "SELECT COUNT(*) FROM transfers");
        long scopedTransfers = count(connection, """
                SELECT COUNT(*) FROM transfers
                WHERE from_route_id IS NOT NULL OR to_route_id IS NOT NULL
                   OR from_trip_id IS NOT NULL OR to_trip_id IS NOT NULL
                   OR service_id IS NOT NULL
                """);
        Map<String, Long> semantics = groupedCounts(connection, """
                SELECT transfer_semantic, COUNT(*)
                FROM transfer_rules
                WHERE raw_transfer_id IS NOT NULL
                GROUP BY transfer_semantic
                ORDER BY transfer_semantic
                """);
        long transferEdges = count(connection, "SELECT COUNT(*) FROM transfer_edges");
        long traversableTransferEdges = count(connection, "SELECT COUNT(*) FROM transfer_edges WHERE is_traversable=1");
        long nonPedestrianGtfsEdges = count(connection, """
                SELECT COUNT(*)
                FROM transfer_edges edge
                JOIN transfers raw ON raw.transfer_id=edge.raw_transfer_id
                WHERE raw.transfer_type IN (3,4,5)
                """);
        long scopedGtfsEdges = count(connection, """
                SELECT COUNT(*)
                FROM transfer_edges edge
                JOIN transfers raw ON raw.transfer_id=edge.raw_transfer_id
                WHERE raw.from_route_id IS NOT NULL OR raw.to_route_id IS NOT NULL
                   OR raw.from_trip_id IS NOT NULL OR raw.to_trip_id IS NOT NULL
                   OR raw.service_id IS NOT NULL
                """);
        long traversableHeuristicEdges = count(connection, """
                SELECT COUNT(*) FROM transfer_edges
                WHERE is_traversable=1 AND edge_kind='NEARBY_AREA_CANDIDATE'
                """);
        long traversableAreaMembershipEdges = count(connection, """
                SELECT COUNT(*) FROM transfer_edges
                WHERE is_traversable=1 AND edge_kind='AREA_MEMBERSHIP_CANDIDATE'
                """);
        long stopFootpaths = count(connection, "SELECT COUNT(*) FROM stop_footpaths");
        long traversableStopFootpaths = count(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE is_traversable=1");
        long unknownStopFootpaths = count(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE is_traversable=0");
        long overDistanceTraversable = count(connection, """
                SELECT COUNT(*) FROM stop_footpaths
                WHERE is_traversable=1 AND distance_meters > 400
                """);
        long zeroTimeTraversable = count(connection, """
                SELECT COUNT(*) FROM stop_footpaths
                WHERE is_traversable=1 AND (min_transfer_seconds IS NULL OR min_transfer_seconds < 120)
                """);
        long multiStopAreas = count(connection, """
                SELECT COUNT(*) FROM (
                    SELECT area_id FROM stop_area_members member
                    JOIN stops stop ON stop.stop_id=member.stop_id
                    WHERE stop.location_type IS NULL OR stop.location_type=0
                    GROUP BY area_id HAVING COUNT(*) > 1
                )
                """);
        long areasWithoutFootpaths = count(connection, """
                WITH multi_stop_areas AS (
                    SELECT member.area_id
                    FROM stop_area_members member
                    JOIN stops stop ON stop.stop_id=member.stop_id
                    WHERE stop.location_type IS NULL OR stop.location_type=0
                    GROUP BY member.area_id
                    HAVING COUNT(DISTINCT member.stop_id) > 1
                )
                SELECT COUNT(*) FROM multi_stop_areas area
                WHERE NOT EXISTS (SELECT 1 FROM stop_footpaths path WHERE path.area_id=area.area_id)
                """);
        long oversizedAreas = count(connection, """
                SELECT COUNT(DISTINCT area_id) FROM stop_footpaths WHERE distance_meters > 400
                """);
        long extremeAreas = count(connection, """
                SELECT COUNT(DISTINCT area_id) FROM stop_footpaths WHERE distance_meters > 700
                """);
        Integer maximumDistance = nullableInteger(connection, "SELECT MAX(distance_meters) FROM stop_footpaths");
        List<String> samples = samples(connection);

        boolean pass = nonPedestrianGtfsEdges == 0
                && scopedGtfsEdges == 0
                && traversableHeuristicEdges == 0
                && traversableAreaMembershipEdges == 0
                && overDistanceTraversable == 0
                && zeroTimeTraversable == 0
                && (!available || areasWithoutFootpaths == 0);
        return new TransferFootpathAuditReport(
                AUDIT_VERSION,
                available,
                pass,
                rawTransfers,
                scopedTransfers,
                Map.copyOf(semantics),
                transferEdges,
                traversableTransferEdges,
                nonPedestrianGtfsEdges,
                scopedGtfsEdges,
                traversableHeuristicEdges,
                traversableAreaMembershipEdges,
                stopFootpaths,
                traversableStopFootpaths,
                unknownStopFootpaths,
                overDistanceTraversable,
                zeroTimeTraversable,
                multiStopAreas,
                areasWithoutFootpaths,
                oversizedAreas,
                extremeAreas,
                maximumDistance,
                List.copyOf(samples)
        );
    }

    private static List<String> samples(Connection connection) throws SQLException {
        List<String> samples = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT area_id, from_stop_id, to_stop_id, distance_meters, quality
                     FROM stop_footpaths
                     WHERE is_traversable=0
                     ORDER BY distance_meters DESC, area_id
                     LIMIT 10
                     """)) {
            while (resultSet.next()) {
                samples.add(resultSet.getString("area_id")
                        + ":" + resultSet.getString("from_stop_id")
                        + "->" + resultSet.getString("to_stop_id")
                        + " distance=" + resultSet.getObject("distance_meters")
                        + " quality=" + resultSet.getString("quality"));
            }
        }
        return samples;
    }

    private static Map<String, Long> groupedCounts(Connection connection, String sql) throws SQLException {
        Map<String, Long> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                result.put(rows.getString(1), rows.getLong(2));
            }
        }
        return result;
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static Integer nullableInteger(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            Object value = resultSet.next() ? resultSet.getObject(1) : null;
            return value == null ? null : ((Number) value).intValue();
        }
    }

    private static String readText(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : "";
        }
    }
}
