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
    public static final String AUDIT_VERSION = "0.2";
    // The NULL trip-scope bucket can contain most feed rows; probe concrete stop pairs instead.
    static final String PROHIBITED_WALKS_QUERY = """
            SELECT COUNT(*) FROM stop_footpaths path
            JOIN stops origin ON origin.stop_id=path.from_stop_id
            JOIN stops destination ON destination.stop_id=path.to_stop_id
            WHERE path.is_traversable=1 AND EXISTS (
                SELECT 1 FROM transfers raw INDEXED BY idx_transfers_from_to
                WHERE raw.from_stop_id IN (origin.stop_id, origin.parent_station)
                  AND raw.to_stop_id IN (destination.stop_id, destination.parent_station)
                  AND raw.from_route_id IS NULL AND raw.to_route_id IS NULL
                  AND raw.from_trip_id IS NULL AND raw.to_trip_id IS NULL AND raw.service_id IS NULL
                  AND (raw.transfer_type=3 OR raw.transfer_type=2 AND raw.min_transfer_time IS NULL
                       OR raw.transfer_type IN (0,1,2) AND raw.min_transfer_time < 0
                       OR raw.transfer_type IN (0,1,2) AND raw.min_transfer_time > path.min_transfer_seconds)
            )
            """;

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
                WHERE is_traversable=1 AND source='SAME_STOP_AREA_GEOMETRY' AND distance_meters > 400
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
        long rawPathways = count(connection, "SELECT COUNT(*) FROM pathways");
        long pathwayFootpaths = count(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE source='GTFS_PATHWAYS' AND is_traversable=1");
        long estimatedFootpaths = count(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE source='SAME_STOP_AREA_GEOMETRY' AND is_traversable=1");
        long invalidWalkComponents = count(connection, """
                SELECT COUNT(*) FROM stop_footpaths path
                WHERE is_traversable=1 AND (
                    walk_seconds IS NULL OR walk_seconds < 0
                    OR transfer_buffer_seconds IS NULL OR transfer_buffer_seconds <> 60
                    OR min_transfer_seconds IS NULL
                    OR min_transfer_seconds < MAX(120, walk_seconds + transfer_buffer_seconds, COALESCE(gtfs_min_transfer_seconds, 0))
                    OR gtfs_min_transfer_seconds < 0
                    OR (source='SAME_STOP_AREA_GEOMETRY' AND (distance_meters IS NULL OR distance_meters < 0))
                    OR (source='GTFS_PATHWAYS' AND (NOT json_valid(pathway_ids)
                        OR json_array_length(pathway_ids)=0 OR pathway_modes < 1 OR pathway_modes > 127))
                    OR (source='GTFS_PATHWAYS' AND EXISTS (
                        SELECT 1 FROM json_each(path.pathway_ids) step
                        LEFT JOIN pathways raw ON raw.pathway_id=step.value WHERE raw.pathway_id IS NULL))
                    OR source NOT IN ('GTFS_PATHWAYS', 'SAME_STOP_AREA_GEOMETRY')
                )
                """);
        invalidWalkComponents += PathwayProvenanceValidator.countInvalid(connection);
        long prohibitedWalks = count(connection, PROHIBITED_WALKS_QUERY);

        boolean pass = nonPedestrianGtfsEdges == 0
                && scopedGtfsEdges == 0
                && traversableHeuristicEdges == 0
                && traversableAreaMembershipEdges == 0
                && overDistanceTraversable == 0
                && zeroTimeTraversable == 0
                && invalidWalkComponents == 0
                && prohibitedWalks == 0
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
                List.copyOf(samples),
                rawPathways,
                pathwayFootpaths,
                estimatedFootpaths,
                invalidWalkComponents,
                prohibitedWalks
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
