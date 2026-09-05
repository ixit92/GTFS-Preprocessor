package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.ixit.gtfs.model.Pathway;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Replays only mapped walks; geometry-only feeds need no additional heap index. */
final class PathwayProvenanceValidator {
    private PathwayProvenanceValidator() {
    }

    static long countInvalid(Connection connection) throws SQLException {
        Map<String, Pathway> raw = new HashMap<>();
        Map<String, String> platformByBoardingArea = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM pathways")) {
            while (rows.next()) {
                raw.put(rows.getString("pathway_id"), new Pathway(rows.getString("pathway_id"),
                        rows.getString("from_stop_id"), rows.getString("to_stop_id"),
                        (Integer) rows.getObject("pathway_mode"), (Integer) rows.getObject("is_bidirectional"),
                        rows.getObject("length") == null ? null : rows.getDouble("length"),
                        (Integer) rows.getObject("traversal_time"), (Integer) rows.getObject("stair_count")));
            }
        }
        if (!raw.isEmpty()) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT stop_id, parent_station FROM stops WHERE location_type=4")) {
                while (rows.next()) platformByBoardingArea.put(rows.getString(1), rows.getString(2));
            }
        }
        long invalid = 0;
        ObjectMapper json = new ObjectMapper();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT * FROM stop_footpaths WHERE source='GTFS_PATHWAYS' AND is_traversable=1")) {
            while (rows.next()) {
                try {
                    String[] ids = json.readValue(rows.getString("pathway_ids"), String[].class);
                    Set<String> position = null;
                    long time = 0;
                    double length = 0;
                    boolean knownLength = true;
                    int modes = 0;
                    for (String id : ids) {
                        Pathway path = raw.get(id);
                        Integer duration = path == null ? null : StationPathwayGraph.traversalSeconds(path);
                        if (duration == null) throw new IllegalArgumentException("Unusable pathway");
                        if (path.bidirectional() == null || path.bidirectional() < 0 || path.bidirectional() > 1
                                || path.mode() == 7 && path.bidirectional() == 1) {
                            throw new IllegalArgumentException("Invalid direction");
                        }
                        String from = rows.getString("from_stop_id");
                        boolean canForward = position == null ? matches(path.fromStopId(), from, platformByBoardingArea)
                                : position.contains(path.fromStopId());
                        boolean canReverse = path.bidirectional() == 1 && (position == null
                                ? matches(path.toStopId(), from, platformByBoardingArea) : position.contains(path.toStopId()));
                        position = new HashSet<>();
                        if (canForward) position.add(path.toStopId());
                        if (canReverse) position.add(path.fromStopId());
                        if (position.isEmpty()) throw new IllegalArgumentException("Disconnected or reversed pathway");
                        time += duration;
                        modes |= 1 << (path.mode() - 1);
                        if (path.lengthMeters() == null) knownLength = false;
                        else length += path.lengthMeters();
                    }
                    String to = rows.getString("to_stop_id");
                    boolean reachesTarget = position != null && position.stream().anyMatch(id -> matches(id, to, platformByBoardingArea));
                    Integer recordedLength = (Integer) rows.getObject("distance_meters");
                    if (!reachesTarget || time != rows.getLong("walk_seconds") || modes != rows.getInt("pathway_modes")
                            || (knownLength ? recordedLength == null || recordedLength != (int) Math.ceil(length) : recordedLength != null)) {
                        invalid++;
                    }
                } catch (java.io.IOException | IllegalArgumentException | NullPointerException ex) {
                    invalid++;
                }
            }
        }
        return invalid;
    }

    private static boolean matches(String node, String platform, Map<String, String> boardingAreas) {
        return node.equals(platform) || platform.equals(boardingAreas.get(node));
    }
}
