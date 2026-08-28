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

public final class AppReadySqliteValidator {
    private AppReadySqliteValidator() {
    }

    public static AppReadySqliteReport validate(Path databasePath) throws SQLException {
        return validate(databasePath, null);
    }

    public static AppReadySqliteReport validate(
            Path databasePath,
            ServiceDayModelReport precomputedServiceDayModel
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            Set<String> tables = readNames(connection, "table");
            Set<String> columns = new LinkedHashSet<>();
            for (String table : tables) {
                columns.addAll(readColumns(connection, table).stream()
                        .map(column -> table + "." + column)
                        .toList());
            }

            Map<String, Boolean> featureFlags = new LinkedHashMap<>();
            featureFlags.put("core_tables", hasCoreTables(tables));
            featureFlags.put("stop_search_tokens", hasRows(connection, tables, "stop_search_tokens"));
            featureFlags.put("area_route_service_summary", hasRows(connection, tables, "area_route_service_summary"));
            featureFlags.put("stop_area_cities", hasRows(connection, tables, "stop_area_cities"));
            featureFlags.put("stop_area_profiles", hasRows(connection, tables, "stop_area_profiles"));
            featureFlags.put("canonical_stop_areas", hasRows(connection, tables, "canonical_stop_areas"));
            featureFlags.put("canonical_stop_area_members", hasRows(connection, tables, "canonical_stop_area_members"));
            featureFlags.put("canonical_stop_area_names", hasRows(connection, tables, "canonical_stop_area_names"));
            featureFlags.put("stop_area_display_names", hasRows(connection, tables, "stop_area_display_names"));
            featureFlags.put("display_name_quality_baseline", tables.contains("display_name_quality_findings"));
            featureFlags.put("service_day_model", hasRows(connection, tables, "service_calendar_summary"));
            featureFlags.put("canonical_stop_area_transfer_edges", hasRows(connection, tables, "canonical_stop_area_transfer_edges"));
            featureFlags.put("stop_area_aliases", hasRows(connection, tables, "stop_area_aliases"));
            featureFlags.put("transfer_edges", hasRows(connection, tables, "transfer_edges"));
            featureFlags.put("stop_footpaths", tables.contains("stop_footpaths"));
            featureFlags.put("route_colors", columns.contains("routes.route_color")
                    && count(connection, tables, "routes", "route_color IS NOT NULL AND trim(route_color) <> ''") > 0);
            featureFlags.put("platform_codes", columns.contains("stops.platform_code")
                    && count(connection, tables, "stops", "platform_code IS NOT NULL AND trim(platform_code) <> ''") > 0);

            Map<String, Long> qualityChecks = new LinkedHashMap<>();
            qualityChecks.put("stop_search_tokens_count", count(connection, tables, "stop_search_tokens"));
            qualityChecks.put("area_route_service_summary_count", count(connection, tables, "area_route_service_summary"));
            qualityChecks.put("stop_area_cities_count", count(connection, tables, "stop_area_cities"));
            qualityChecks.put("stop_area_cities_official_boundary_count", count(
                    connection,
                    tables,
                    "stop_area_cities",
                    "quality = 'OFFICIAL_BOUNDARY'"
            ));
            qualityChecks.put("stop_area_cities_unresolved_count", count(
                    connection,
                    tables,
                    "stop_area_cities",
                    "quality = 'UNRESOLVED'"
            ));
            qualityChecks.put("stop_area_profiles_count", count(connection, tables, "stop_area_profiles"));
            qualityChecks.put("stop_area_profiles_nonempty_line_labels", count(
                    connection,
                    tables,
                    "stop_area_profiles",
                    "line_labels IS NOT NULL AND trim(line_labels) <> ''"
            ));
            qualityChecks.put("rail_stop_area_profiles_count", count(
                    connection,
                    tables,
                    "stop_area_profiles",
                    "has_rail_service <> 0"
            ));
            qualityChecks.put("rail_stop_area_profiles_with_line_labels", count(
                    connection,
                    tables,
                    "stop_area_profiles",
                    "has_rail_service <> 0 AND line_labels IS NOT NULL AND trim(line_labels) <> ''"
            ));
            qualityChecks.put("stop_area_aliases_count", count(connection, tables, "stop_area_aliases"));
            qualityChecks.put("canonical_stop_areas_count", count(connection, tables, "canonical_stop_areas"));
            qualityChecks.put("canonical_stop_area_members_count", count(connection, tables, "canonical_stop_area_members"));
            qualityChecks.put("canonical_stop_area_names_count", count(connection, tables, "canonical_stop_area_names"));
            qualityChecks.put("stop_area_display_names_count", count(connection, tables, "stop_area_display_names"));
            qualityChecks.put("canonical_stop_area_transfer_edges_count", count(connection, tables, "canonical_stop_area_transfer_edges"));
            qualityChecks.put("canonical_stop_area_visible_suggestions", countIfColumnExists(
                    connection,
                    tables,
                    columns,
                    "canonical_stop_area_members",
                    "is_visible_suggestion",
                    "is_visible_suggestion <> 0"
            ));
            qualityChecks.put("canonical_stop_area_routing_members", countIfColumnExists(
                    connection,
                    tables,
                    columns,
                    "canonical_stop_area_members",
                    "is_primary_for_routing",
                    "is_primary_for_routing <> 0"
            ));
            qualityChecks.put("canonical_stop_area_good_display_names", count(
                    connection,
                    tables,
                    "canonical_stop_area_names",
                    "display_quality = 'GOOD'"
            ));
            qualityChecks.put("public_stop_area_good_display_names", count(
                    connection,
                    tables,
                    "stop_area_display_names",
                    "display_quality = 'GOOD'"
            ));
            qualityChecks.put("canonical_stop_area_multi_member_count", count(
                    connection,
                    tables,
                    "canonical_stop_areas",
                    "member_count > 1"
            ));
            qualityChecks.put("canonical_stop_area_bus_feeder_members", count(
                    connection,
                    tables,
                    "canonical_stop_area_members",
                    "member_role = 'BUS_FEEDER'"
            ));
            qualityChecks.put("canonical_stop_area_technical_display_names", count(
                    connection,
                    tables,
                    "canonical_stop_areas",
                    "display_quality = 'TECHNICAL'"
            ));
            qualityChecks.put("transfer_edges_count", count(connection, tables, "transfer_edges"));
            qualityChecks.put("stop_footpaths_count", count(connection, tables, "stop_footpaths"));
            qualityChecks.put("stop_footpaths_traversable_count", count(
                    connection, tables, "stop_footpaths", "is_traversable=1"
            ));
            qualityChecks.put("stop_footpaths_unknown_count", count(
                    connection, tables, "stop_footpaths", "is_traversable=0"
            ));
            qualityChecks.put("routes_with_color_count", countIfColumnExists(
                    connection,
                    tables,
                    columns,
                    "routes",
                    "route_color",
                    "route_color IS NOT NULL AND trim(route_color) <> ''"
            ));
            qualityChecks.put("platform_codes_count", countIfColumnExists(
                    connection,
                    tables,
                    columns,
                    "stops",
                    "platform_code",
                    "platform_code IS NOT NULL AND trim(platform_code) <> ''"
            ));
            qualityChecks.put("calendar_rows_count", count(connection, tables, "calendar"));
            qualityChecks.put("calendar_dates_rows_count", count(connection, tables, "calendar_dates"));

            ServiceDayModelReport serviceDayModel = precomputedServiceDayModel == null
                    ? ServiceDayModelAuditor.audit(connection)
                    : precomputedServiceDayModel;
            qualityChecks.put("service_day_model_service_count", serviceDayModel.serviceCount());
            qualityChecks.put("service_day_model_unresolved_trip_services", serviceDayModel.unresolvedTripServiceCount());
            qualityChecks.put("service_day_model_invalid_weekday_flags", serviceDayModel.invalidWeekdayFlagCount());
            qualityChecks.put("service_day_model_invalid_calendar_ranges", serviceDayModel.invalidCalendarRangeCount());
            qualityChecks.put("service_day_model_invalid_exception_dates", serviceDayModel.invalidExceptionDateCount());
            qualityChecks.put("service_day_model_invalid_exception_types", serviceDayModel.invalidExceptionTypeCount());
            qualityChecks.put("service_day_model_invalid_iana_timezone_services", serviceDayModel.invalidIanaTimezoneServiceCount());
            qualityChecks.put("service_day_model_unknown_timezone_trip_services", serviceDayModel.unknownTimezoneTripServiceCount());
            qualityChecks.put("service_day_model_multiple_timezone_trip_services", serviceDayModel.multipleTimezoneTripServiceCount());
            qualityChecks.put("service_day_model_overflow_stop_times", serviceDayModel.overflowStopTimeCount());

            DisplayNameAuditReport displayNameAudit = DisplayNameAuditor.audit(connection);
            qualityChecks.put("display_name_audit_scanned_count", displayNameAudit.scannedNames());
            qualityChecks.put("display_name_format_mismatch_count", displayNameAudit.formatMismatches());
            qualityChecks.put("display_name_municipality_only_count", displayNameAudit.municipalityOnlyNames());
            qualityChecks.put("display_name_duplicate_city_prefix_count", displayNameAudit.duplicateCityNamePrefixes());
            qualityChecks.put("display_name_matching_city_code_prefix_count", displayNameAudit.matchingCityCodePrefixes());
            qualityChecks.put("display_name_matching_city_qualifier_count", displayNameAudit.matchingCityQualifiers());
            qualityChecks.put("display_name_suspicious_unknown_prefix_count", displayNameAudit.suspiciousUnknownPrefixes());

            DisplayNameQualityBaselineReport displayNameQualityBaseline =
                    DisplayNameQualityBaselineAuditor.audit(connection);
            qualityChecks.put("display_name_quality_finding_count", displayNameQualityBaseline.findingCount());
            qualityChecks.put("display_name_quality_prefix_count", displayNameQualityBaseline.prefixFindingCount());
            qualityChecks.put("display_name_quality_municipality_only_count", displayNameQualityBaseline.municipalityOnlyFindingCount());
            qualityChecks.put("display_name_quality_coverage_gap_count", displayNameQualityBaseline.coverageGapCount());
            qualityChecks.put("display_name_quality_destructive_action_count", displayNameQualityBaseline.destructiveActionCount());

            List<String> warnings = warnings(
                    featureFlags,
                    qualityChecks,
                    displayNameAudit,
                    displayNameQualityBaseline,
                    serviceDayModel
            );
            boolean appReady = warnings.isEmpty();
            return new AppReadySqliteReport(
                    appReady,
                    Map.copyOf(featureFlags),
                    Map.copyOf(qualityChecks),
                    displayNameAudit,
                    displayNameQualityBaseline,
                    serviceDayModel,
                    List.copyOf(warnings)
            );
        }
    }

    private static List<String> warnings(
            Map<String, Boolean> featureFlags,
            Map<String, Long> qualityChecks,
            DisplayNameAuditReport displayNameAudit,
            DisplayNameQualityBaselineReport displayNameQualityBaseline,
            ServiceDayModelReport serviceDayModel
    ) {
        List<String> warnings = new ArrayList<>();
        requireFlag(featureFlags, warnings, "core_tables", "Core routing tables incomplete.");
        requireFlag(featureFlags, warnings, "stop_search_tokens", "stop_search_tokens missing or empty.");
        requireFlag(featureFlags, warnings, "area_route_service_summary", "area_route_service_summary missing or empty.");
        requireFlag(featureFlags, warnings, "stop_area_cities", "stop_area_cities missing or empty.");
        requireFlag(featureFlags, warnings, "stop_area_profiles", "stop_area_profiles missing or empty.");
        requireFlag(featureFlags, warnings, "canonical_stop_areas", "canonical_stop_areas missing or empty.");
        requireFlag(featureFlags, warnings, "canonical_stop_area_members", "canonical_stop_area_members missing or empty.");
        requireFlag(featureFlags, warnings, "canonical_stop_area_names", "canonical_stop_area_names missing or empty.");
        requireFlag(featureFlags, warnings, "stop_area_display_names", "stop_area_display_names missing or empty.");
        requireFlag(featureFlags, warnings, "display_name_quality_baseline", "display_name_quality_findings table is missing.");
        requireFlag(featureFlags, warnings, "service_day_model", "service_calendar_summary missing or empty.");
        requireFlag(featureFlags, warnings, "canonical_stop_area_transfer_edges", "canonical_stop_area_transfer_edges missing or empty.");
        requireFlag(featureFlags, warnings, "stop_area_aliases", "stop_area_aliases missing or empty.");
        requireFlag(featureFlags, warnings, "transfer_edges", "transfer_edges missing or empty.");
        requireFlag(featureFlags, warnings, "stop_footpaths", "stop_footpaths table is missing.");
        if (qualityChecks.getOrDefault("stop_area_profiles_nonempty_line_labels", 0L) <= 0) {
            warnings.add("stop_area_profiles.line_labels is empty; StopSearch line chips need Android fallback.");
        }
        if (qualityChecks.getOrDefault("rail_stop_area_profiles_count", 0L) > 0
                && qualityChecks.getOrDefault("rail_stop_area_profiles_with_line_labels", 0L) <= 0) {
            warnings.add("Rail StopArea profiles have no line_labels.");
        }
        if (qualityChecks.getOrDefault("canonical_stop_area_routing_members", 0L) <= 0) {
            warnings.add("canonical_stop_area_members has no routing-visible members.");
        }
        if (!displayNameAudit.available()) {
            warnings.add("Display-name audit could not run because required tables are missing.");
        } else if (!displayNameAudit.pass()) {
            warnings.add("Display-name contract violations remain: "
                    + displayNameAudit.residualCount()
                    + ", samples: "
                    + String.join(", ", displayNameAudit.samples()));
        }
        if (!displayNameQualityBaseline.available()) {
            warnings.add("Display-name quality baseline is unavailable.");
        } else if (!displayNameQualityBaseline.pass()) {
            warnings.add("Display-name quality baseline coverage failed: gaps="
                    + displayNameQualityBaseline.coverageGapCount()
                    + ", destructive_actions="
                    + displayNameQualityBaseline.destructiveActionCount());
        }
        if (!serviceDayModel.available()) {
            warnings.add("Service-day model is unavailable.");
        } else if (!serviceDayModel.pass()) {
            warnings.add("Service-day model failed: unresolved_trip_services="
                    + serviceDayModel.unresolvedTripServiceCount()
                    + ", invalid_weekday_flags="
                    + serviceDayModel.invalidWeekdayFlagCount()
                    + ", invalid_calendar_ranges="
                    + serviceDayModel.invalidCalendarRangeCount()
                    + ", invalid_exception_dates="
                    + serviceDayModel.invalidExceptionDateCount()
                    + ", invalid_exception_types="
                    + serviceDayModel.invalidExceptionTypeCount()
                    + ", invalid_iana_timezone_services="
                    + serviceDayModel.invalidIanaTimezoneServiceCount()
                    + ", unknown_timezone_trip_services="
                    + serviceDayModel.unknownTimezoneTripServiceCount()
                    + ", multiple_timezone_trip_services="
                    + serviceDayModel.multipleTimezoneTripServiceCount());
        }
        return warnings;
    }

    private static void requireFlag(
            Map<String, Boolean> featureFlags,
            List<String> warnings,
            String key,
            String warning
    ) {
        if (!featureFlags.getOrDefault(key, false)) {
            warnings.add(warning);
        }
    }

    private static boolean hasCoreTables(Set<String> tables) {
        return tables.contains("stops")
                && tables.contains("stop_areas")
                && tables.contains("stop_area_members")
                && tables.contains("routes")
                && tables.contains("trips")
                && tables.contains("stop_times")
                && tables.contains("calendar")
                && tables.contains("calendar_dates");
    }

    private static boolean hasRows(Connection connection, Set<String> tables, String table) throws SQLException {
        return count(connection, tables, table) > 0;
    }

    private static long count(Connection connection, Set<String> tables, String table) throws SQLException {
        return count(connection, tables, table, null);
    }

    private static long count(
            Connection connection,
            Set<String> tables,
            String table,
            String whereClause
    ) throws SQLException {
        if (!tables.contains(table)) {
            return 0;
        }
        String sql = "SELECT COUNT(*) FROM " + table;
        if (whereClause != null && !whereClause.isBlank()) {
            sql += " WHERE " + whereClause;
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    private static long countIfColumnExists(
            Connection connection,
            Set<String> tables,
            Set<String> columns,
            String table,
            String column,
            String whereClause
    ) throws SQLException {
        if (!columns.contains(table + "." + column)) {
            return 0;
        }
        return count(connection, tables, table, whereClause);
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
}
