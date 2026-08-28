package de.ixit.gtfs;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class RoutingCompatibilityAuditor {
    public static final String AUDIT_VERSION = SqliteContract.PREPROCESSOR_VERSION;

    private RoutingCompatibilityAuditor() {
    }

    public static RoutingCompatibilityAuditReport audit(Path databasePath, SqliteContractReport contractReport) throws SQLException {
        return audit(databasePath, contractReport, ServiceDayModelAuditor.audit(databasePath));
    }

    public static RoutingCompatibilityAuditReport audit(
            Path databasePath,
            SqliteContractReport contractReport,
            ServiceDayModelReport serviceDayModel
    ) throws SQLException {
        List<RoutingCompatibilityAuditItem> items = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            requirePolicy(items, "stop_id_policy", contractReport.stopIdPolicy(), "original_gtfs_stop_id",
                    "stop_id remains the concrete original GTFS stop ID.");
            requirePolicy(items, "area_id_policy", contractReport.areaIdPolicy(), "parent_station_or_stop_id",
                    "area_id remains an IXIT-derived StopArea identifier.");
            requirePolicy(items, "time_model", contractReport.timeModel(), "seconds_since_service_day_start",
                    "Routing must interpret arrival_seconds and departure_seconds as seconds since service day start.");
            requirePolicy(items, "search_tokens_policy", contractReport.searchTokensPolicy(), "search_only_not_routing",
                    "SearchTokens are search/UI helpers only.");
            requirePolicy(items, "service_day_resolution_policy",
                    contractReport.metadata().get("service_day_resolution_policy"),
                    SqliteContract.SERVICE_DAY_RESOLUTION_POLICY,
                    "calendar_dates additions and removals override the base weekly calendar.");
            requirePolicy(items, "service_day_timezone_policy",
                    contractReport.metadata().get("service_day_timezone_policy"),
                    SqliteContract.SERVICE_DAY_TIMEZONE_POLICY,
                    "Service dates are interpreted in the recorded GTFS agency timezone.");
            requirePolicy(items, "transfer_semantics_policy",
                    contractReport.metadata().get("transfer_semantics_policy"),
                    SqliteContract.TRANSFER_SEMANTICS_POLICY,
                    "GTFS transfer scope and non-walking semantics remain separate from pedestrian edges.");
            requirePolicy(items, "footpath_policy",
                    contractReport.metadata().get("footpath_policy"),
                    SqliteContract.FOOTPATH_POLICY,
                    "Same-area membership alone never proves a zero-distance walking path.");

            requireKnownContractVersion(items, contractReport.contractVersion());
            requireTable(items, contractReport.rowCounts(), "stop_areas", "User selection can later target StopAreas via area_id.");
            requireTable(items, contractReport.rowCounts(), "stop_area_members", "StopArea selections can be resolved to concrete stop_id members.");
            requireTable(items, contractReport.rowCounts(), "stop_search_tokens", "SearchTokens can support UI lookup but not routing decisions.");
            requireTable(items, contractReport.rowCounts(), "hub_profiles", "HubProfiles are available as analysis/prioritization/explanation data.");
            requireTable(items, contractReport.rowCounts(), "route_axes", "RouteAxis is available as line-structure/diagnostic data.");
            requireTable(items, contractReport.rowCounts(), "transfer_rules", "TransferRules are available as helper data, not final transfer decisions.");
            requireTable(items, contractReport.rowCounts(), "stop_footpaths", "Concrete same-area stop footpath estimates are available with provenance and confidence.");
            requireTable(items, contractReport.rowCounts(), "service_calendar_summary", "Service IDs can be checked against a date before any future routing decision.");

            requireColumn(items, connection, "stop_times", "stop_id", "Concrete schedules remain stop_id based.");
            requireColumn(items, connection, "stop_times", "arrival_seconds", "Arrival times are stored as routing-readable seconds.");
            requireColumn(items, connection, "stop_times", "departure_seconds", "Departure times are stored as routing-readable seconds.");
            requireColumn(items, connection, "trips", "service_id", "Routing must later apply service calendars through trips.service_id.");
            requireColumn(items, connection, "calendar", "service_id", "calendar.service_id is present for future service-day validation when feed data exists.");
            requireColumn(items, connection, "calendar_dates", "service_id", "calendar_dates.service_id is present for service-day exceptions.");
            requireColumn(items, connection, "calendar_dates", "date", "calendar_dates.date is present for service-day exceptions.");
            requireColumn(items, connection, "calendar_dates", "exception_type", "calendar_dates.exception_type distinguishes added and removed service days.");
            requireColumn(items, connection, "calendar_dates", "exception_action", "Service-day additions and removals have explicit IXIT action labels.");
            requireColumn(items, connection, "service_calendar_summary", "service_timezone", "Service-day timezone context remains explicit.");

            if (serviceDayModel.pass()) {
                items.add(pass("service_day_model", "Combined calendar/calendar_dates model is complete for trip services; services="
                        + serviceDayModel.serviceCount()
                        + ", overflow_stop_times="
                        + serviceDayModel.overflowStopTimeCount()
                        + "."));
            } else {
                items.add(warn("service_day_model", "Service-day model contains unresolved or invalid data; unresolved_trip_services="
                        + serviceDayModel.unresolvedTripServiceCount()
                        + "."));
            }

            long tripCount = contractReport.rowCounts().getOrDefault("trips", 0L);
            long stopTimeCount = contractReport.rowCounts().getOrDefault("stop_times", 0L);
            if (tripCount > 0 && stopTimeCount > 0) {
                items.add(pass("concrete_trip_validation", "Concrete rides can be validated via trips and stop_times."));
            } else {
                items.add(warn("concrete_trip_validation", "Trips or StopTimes are empty; routing cannot validate concrete rides from this feed."));
            }

            long maxTime = serviceDayModel.maximumServiceDaySeconds();
            if (maxTime > 86_400) {
                items.add(pass("gtfs_over_24h_times", "Observed GTFS service-day overflow time in seconds: " + maxTime + "."));
            } else {
                items.add(info("gtfs_over_24h_times", "No >24:00:00 time observed in this feed, but the contract allows service-day overflow seconds."));
            }

            if (contractReport.rowCounts().getOrDefault("calendar", 0L) > 0) {
                items.add(pass("calendar_service_binding", "calendar rows exist; routing must later bind trips.service_id to service availability."));
            } else {
                items.add(warn("calendar_service_binding", "calendar.txt is absent or empty; routing integration must define how service availability is provided before runtime use."));
            }

            long calendarDateRows = contractReport.rowCounts().getOrDefault("calendar_dates", 0L);
            if (calendarDateRows > 0) {
                items.add(pass("calendar_dates_service_exceptions", "calendar_dates rows exist; routing can later apply exception_type service-day overrides. Rows: " + calendarDateRows + "."));
            } else {
                items.add(info("calendar_dates_service_exceptions", "calendar_dates.txt is absent or empty; contract table is present with zero rows."));
            }

            TransferFootpathAuditReport transferAudit = TransferFootpathAuditor.audit(connection);
            if (transferAudit.pass()) {
                items.add(pass("transfer_footpath_semantics", "Transfer and footpath separation audit passed; non-walking or scoped GTFS edges exposed as generic walk edges: 0."));
            } else {
                items.add(warn("transfer_footpath_semantics", "Transfer and footpath separation audit failed; routing consumption is blocked."));
            }
        }
        return RoutingCompatibilityAuditReport.from(AUDIT_VERSION, items);
    }

    private static void requirePolicy(List<RoutingCompatibilityAuditItem> items, String id, String actual, String expected, String summary) {
        if (expected.equals(actual)) {
            items.add(pass(id, summary));
        } else {
            items.add(warn(id, "Expected " + expected + " but contract reports " + actual + "."));
        }
    }

    private static void requireKnownContractVersion(List<RoutingCompatibilityAuditItem> items, String contractVersion) {
        if (SqliteContract.SUPPORTED_CONTRACT_VERSIONS.contains(contractVersion)) {
            items.add(pass("runtime_contract_gate", "Future runtime must accept only known contract versions; this file reports " + contractVersion + "."));
        } else {
            items.add(warn("runtime_contract_gate", "Unknown contract_version for this preprocessor: " + contractVersion + "."));
        }
    }

    private static void requireTable(List<RoutingCompatibilityAuditItem> items, Map<String, Long> rowCounts, String tableName, String summary) {
        if (rowCounts.containsKey(tableName)) {
            items.add(pass("table_" + tableName, summary + " Rows: " + rowCounts.get(tableName) + "."));
        } else {
            items.add(warn("table_" + tableName, "Missing table required by routing compatibility audit: " + tableName));
        }
    }

    private static void requireColumn(List<RoutingCompatibilityAuditItem> items, Connection connection, String tableName, String columnName, String summary) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("name"))) {
                    items.add(pass("column_" + tableName + "_" + columnName, summary));
                    return;
                }
            }
        }
        items.add(warn("column_" + tableName + "_" + columnName, "Missing column needed for routing compatibility: " + tableName + "." + columnName));
    }

    private static RoutingCompatibilityAuditItem pass(String id, String summary) {
        return new RoutingCompatibilityAuditItem(id, "PASS", summary);
    }

    private static RoutingCompatibilityAuditItem warn(String id, String summary) {
        return new RoutingCompatibilityAuditItem(id, "WARN", summary);
    }

    private static RoutingCompatibilityAuditItem info(String id, String summary) {
        return new RoutingCompatibilityAuditItem(id, "INFO", summary);
    }

    public record RoutingCompatibilityAuditReport(
            String auditVersion,
            int passCount,
            int warnCount,
            int infoCount,
            List<RoutingCompatibilityAuditItem> items
    ) {
        public static RoutingCompatibilityAuditReport from(String auditVersion, List<RoutingCompatibilityAuditItem> items) {
            int passCount = 0;
            int warnCount = 0;
            int infoCount = 0;
            for (RoutingCompatibilityAuditItem item : items) {
                if ("PASS".equals(item.status())) {
                    passCount++;
                } else if ("WARN".equals(item.status())) {
                    warnCount++;
                } else if ("INFO".equals(item.status())) {
                    infoCount++;
                }
            }
            return new RoutingCompatibilityAuditReport(auditVersion, passCount, warnCount, infoCount, List.copyOf(items));
        }
    }

    public record RoutingCompatibilityAuditItem(String id, String status, String summary) {
    }
}
