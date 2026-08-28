package de.ixit.gtfs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IndexSmokeChecker {
    private IndexSmokeChecker() {
    }

    public static Map<String, String> run(Path databasePath) {
        Map<String, String> results = new LinkedHashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            results.put("stop_search_token_lookup", check(connection, "SELECT token FROM stop_search_tokens WHERE token = (SELECT token FROM stop_search_tokens LIMIT 1) LIMIT 1"));
            results.put("stop_area_members_lookup", check(connection, "SELECT stop_id FROM stop_area_members WHERE area_id = (SELECT area_id FROM stop_area_members LIMIT 1) LIMIT 1"));
            results.put("stop_area_aliases_lookup", check(connection, "SELECT area_id FROM stop_area_aliases WHERE alias_normalized = (SELECT alias_normalized FROM stop_area_aliases LIMIT 1) LIMIT 1"));
            results.put("stop_area_profiles_lookup", check(connection, "SELECT area_id FROM stop_area_profiles WHERE profile_class = (SELECT profile_class FROM stop_area_profiles LIMIT 1) LIMIT 1"));
            results.put("canonical_stop_areas_lookup", check(connection, "SELECT canonical_area_id FROM canonical_stop_areas WHERE canonical_display_name = (SELECT canonical_display_name FROM canonical_stop_areas LIMIT 1) LIMIT 1"));
            results.put("canonical_stop_area_names_lookup", check(connection, "SELECT canonical_area_id FROM canonical_stop_area_names WHERE display_name = (SELECT display_name FROM canonical_stop_area_names LIMIT 1) LIMIT 1"));
            results.put("stop_area_display_names_lookup", check(connection, "SELECT area_id FROM stop_area_display_names WHERE public_display_name_normalized = (SELECT public_display_name_normalized FROM stop_area_display_names LIMIT 1) LIMIT 1"));
            results.put("canonical_stop_area_members_lookup", check(connection, "SELECT canonical_area_id FROM canonical_stop_area_members WHERE area_id = (SELECT area_id FROM canonical_stop_area_members LIMIT 1) LIMIT 1"));
            results.put("stop_times_by_stop_departure_lookup", check(connection, "SELECT trip_id FROM stop_times WHERE stop_id = (SELECT stop_id FROM stop_times LIMIT 1) ORDER BY departure_seconds LIMIT 1"));
            results.put("service_calendar_status_lookup", check(connection, "SELECT service_id FROM service_calendar_summary WHERE status = (SELECT status FROM service_calendar_summary LIMIT 1) LIMIT 1"));
            results.put("route_axes_by_route_lookup", check(connection, "SELECT axis_id FROM route_axes WHERE route_id = (SELECT route_id FROM route_axes LIMIT 1) LIMIT 1"));
            results.put("transfer_rules_by_area_lookup", check(connection, "SELECT transfer_rule_id FROM transfer_rules WHERE from_area_id = (SELECT from_area_id FROM transfer_rules LIMIT 1) LIMIT 1"));
            results.put("transfer_edges_by_area_lookup", check(connection, "SELECT transfer_edge_id FROM transfer_edges WHERE from_stop_area_id = (SELECT from_stop_area_id FROM transfer_edges LIMIT 1) LIMIT 1"));
            results.put("stop_footpaths_by_stop_lookup", check(connection, "SELECT footpath_id FROM stop_footpaths WHERE from_stop_id = (SELECT from_stop_id FROM stop_footpaths LIMIT 1) LIMIT 1"));
        } catch (SQLException ex) {
            results.put("sqlite_connection", "FAILED: " + ex.getMessage());
        }
        return Map.copyOf(results);
    }

    private static String check(Connection connection, String sql) {
        try (var statement = connection.createStatement()) {
            statement.executeQuery(sql).close();
            return "OK";
        } catch (SQLException ex) {
            return "FAILED: " + ex.getMessage();
        }
    }
}
