package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public final class SqliteContract {
    public static final String SCHEMA_VERSION = "0.1";
    public static final String PREPROCESSOR_VERSION = "0.9.7";
    public static final String CONTRACT_NAME = "IXIT_GTFS_SQLITE_CONTRACT";
    public static final String LEGACY_CONTRACT_VERSION = "0.5";
    public static final String COMPATIBILITY_CONTRACT_VERSION = "0.6";
    public static final String DISPLAY_CONTRACT_VERSION = "0.7";
    public static final String CONTRACT_VERSION = "0.8";
    public static final String APP_RUNTIME_CONTRACT_VERSION = "0.8";
    public static final List<String> SUPPORTED_CONTRACT_VERSIONS = List.of(
            LEGACY_CONTRACT_VERSION,
            COMPATIBILITY_CONTRACT_VERSION,
            DISPLAY_CONTRACT_VERSION,
            CONTRACT_VERSION
    );
    public static final String TIME_MODEL = "seconds_since_service_day_start";
    public static final String STOP_ID_POLICY = "original_gtfs_stop_id";
    public static final String AREA_ID_POLICY = "parent_station_or_stop_id";
    public static final String SEARCH_TOKENS_POLICY = "search_only_not_routing";
    public static final String DISPLAY_NAME_TRANSFORMATION_VERSION = "2";
    public static final String DISPLAY_NAME_TRANSFORMATION_POLICY = "explainable_search_ui_only";
    public static final String SERVICE_DAY_RESOLUTION_POLICY = "calendar_dates_override_calendar";
    public static final String SERVICE_DAY_TIMEZONE_POLICY = "gtfs_service_date_in_agency_timezone";
    public static final String SERVICE_DAY_TIME_OVERFLOW_POLICY = "preserve_seconds_since_service_day_start";
    public static final String TRANSFER_SEMANTICS_POLICY = "preserve_gtfs_scope_and_separate_non_walking";
    public static final String FOOTPATH_POLICY = "concrete_stop_estimates_never_area_membership_as_zero_distance";

    public static final List<String> EXPECTED_TABLES = List.of(
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "transfers",
            "calendar",
            "calendar_dates",
            "feed_agencies",
            "service_calendar_summary",
            "stop_search_tokens",
            "hub_profiles",
            "route_axes",
            "route_axis_stops",
            "transfer_rules",
            "stop_footpaths",
            "ixit_metadata"
    );

    public static final List<String> ADDITIVE_TABLES = List.of(
            "stop_area_aliases",
            "area_route_service_summary",
            "stop_area_cities",
            "stop_area_profiles",
            "canonical_stop_areas",
            "canonical_stop_area_members",
            "canonical_stop_area_names",
            "stop_area_display_names",
            "display_name_quality_findings",
            "canonical_stop_area_transfer_edges",
            "transfer_edges",
            "shapes"
    );

    public static final List<String> EXPECTED_INDEXES = List.of(
            "idx_stops_parent_station",
            "idx_stop_area_members_area_id",
            "idx_stop_area_members_stop_id",
            "idx_trips_route_id",
            "idx_trips_service_id",
            "idx_stop_times_trip_sequence",
            "idx_stop_times_stop_departure",
            "idx_stop_times_stop_trip",
            "idx_transfers_from_to",
            "idx_transfers_type",
            "idx_transfers_trip_scope",
            "idx_calendar_service_id",
            "idx_calendar_dates_service_id",
            "idx_calendar_dates_date",
            "idx_calendar_dates_service_date",
            "idx_service_calendar_status",
            "idx_service_calendar_timezone",
            "idx_service_calendar_trip_count",
            "idx_stop_search_tokens_token",
            "idx_stop_search_tokens_stop_id",
            "idx_stop_search_tokens_area_id",
            "idx_stop_search_tokens_token_area",
            "idx_hub_profiles_hub_level",
            "idx_hub_profiles_route_count",
            "idx_hub_profiles_trip_count",
            "idx_hub_profiles_transfer_candidate_score",
            "idx_route_axes_route_id",
            "idx_route_axes_direction_id",
            "idx_route_axes_route_direction",
            "idx_route_axes_first_area_id",
            "idx_route_axes_last_area_id",
            "idx_route_axis_stops_area_id",
            "idx_route_axis_stops_axis_id",
            "idx_transfer_rules_from_area_id",
            "idx_transfer_rules_to_area_id",
            "idx_transfer_rules_from_to_area",
            "idx_transfer_rules_source",
            "idx_transfer_rules_confidence",
            "idx_transfer_rules_semantic",
            "idx_transfer_rules_scope",
            "idx_transfer_rules_pedestrian",
            "idx_transfer_rules_raw",
            "idx_stop_footpaths_area",
            "idx_stop_footpaths_from",
            "idx_stop_footpaths_to",
            "idx_stop_footpaths_traversable",
            "idx_stop_footpaths_quality"
    );

    public static final List<String> ADDITIVE_INDEXES = List.of(
            "idx_stop_area_aliases_area_id",
            "idx_stop_area_aliases_normalized",
            "idx_stop_area_aliases_area_normalized",
            "idx_stop_area_aliases_priority",
            "idx_area_route_service_summary_area_id",
            "idx_area_route_service_summary_route_id",
            "idx_area_route_service_summary_route_type",
            "idx_stop_area_profiles_profile_class",
            "idx_stop_area_profiles_rail_service",
            "idx_stop_area_profiles_bus_only",
            "idx_stop_area_profiles_search_priority",
            "idx_stop_area_cities_city",
            "idx_stop_area_cities_municipality",
            "idx_stop_area_cities_quality",
            "idx_canonical_stop_areas_display_name",
            "idx_canonical_stop_areas_primary_area",
            "idx_canonical_stop_area_names_display_name",
            "idx_canonical_stop_area_names_normalized",
            "idx_canonical_stop_area_names_city_name",
            "idx_stop_area_display_names_canonical",
            "idx_stop_area_display_names_public",
            "idx_stop_area_display_names_normalized",
            "idx_stop_area_display_names_city",
            "idx_display_name_quality_type",
            "idx_display_name_quality_classification",
            "idx_display_name_quality_prefix",
            "idx_display_name_quality_area",
            "idx_canonical_stop_area_members_area_id",
            "idx_canonical_stop_area_members_canonical",
            "idx_canonical_stop_area_members_role",
            "idx_canonical_stop_area_members_search",
            "idx_canonical_stop_area_members_routing",
            "idx_canonical_stop_area_transfer_edges_from",
            "idx_canonical_stop_area_transfer_edges_to",
            "idx_canonical_stop_area_transfer_edges_family",
            "idx_transfer_edges_from_area",
            "idx_transfer_edges_to_area",
            "idx_transfer_edges_from_to_area",
            "idx_transfer_edges_quality",
            "idx_transfer_edges_source",
            "idx_transfer_edges_traversable",
            "idx_transfer_edges_kind",
            "idx_transfer_edges_raw"
    );

    public static final List<String> APP_RUNTIME_EXPECTED_INDEXES = List.of(
            "idx_stops_parent_station",
            "idx_stop_area_members_area_id",
            "idx_stop_area_members_stop_id",
            "idx_trips_service_id",
            "idx_stop_times_trip_sequence",
            "idx_stop_times_stop_departure",
            "idx_calendar_service_id",
            "idx_calendar_dates_service_id",
            "idx_calendar_dates_date",
            "idx_calendar_dates_service_date",
            "idx_service_calendar_status",
            "idx_service_calendar_timezone",
            "idx_service_calendar_trip_count",
            "idx_stop_search_tokens_token",
            "idx_stop_search_tokens_area_id",
            "idx_stop_search_tokens_token_area",
            "idx_transfers_from_to",
            "idx_transfers_type",
            "idx_transfers_trip_scope",
            "idx_stop_footpaths_area",
            "idx_stop_footpaths_from",
            "idx_stop_footpaths_to",
            "idx_stop_footpaths_traversable"
    );

    public static final List<String> APP_RUNTIME_ADDITIVE_INDEXES = List.of(
            "idx_stop_area_aliases_area_id",
            "idx_stop_area_aliases_normalized",
            "idx_stop_area_aliases_area_normalized",
            "idx_stop_area_cities_city",
            "idx_stop_area_cities_municipality",
            "idx_canonical_stop_areas_display_name",
            "idx_canonical_stop_areas_primary_area",
            "idx_canonical_stop_area_names_display_name",
            "idx_canonical_stop_area_names_normalized",
            "idx_stop_area_display_names_canonical",
            "idx_stop_area_display_names_normalized",
            "idx_display_name_quality_type",
            "idx_display_name_quality_classification",
            "idx_display_name_quality_prefix",
            "idx_display_name_quality_area",
            "idx_canonical_stop_area_members_area_id",
            "idx_canonical_stop_area_members_canonical",
            "idx_canonical_stop_area_members_search",
            "idx_canonical_stop_area_members_routing",
            "idx_canonical_stop_area_transfer_edges_from",
            "idx_canonical_stop_area_transfer_edges_to",
            "idx_canonical_stop_area_transfer_edges_family",
            "idx_transfer_edges_from_area",
            "idx_transfer_edges_to_area",
            "idx_transfer_edges_from_to_area",
            "idx_transfer_edges_traversable",
            "idx_transfer_edges_kind",
            "idx_transfer_edges_raw"
    );

    public static final Map<String, List<String>> REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("ixit_metadata", List.of("key", "value")),
            Map.entry("stop_times", List.of("arrival_seconds", "departure_seconds", "stop_id", "shape_dist_traveled")),
            Map.entry("trips", List.of("trip_id", "shape_id")),
            Map.entry("shapes", List.of("shape_id", "shape_pt_sequence", "shape_pt_lat", "shape_pt_lon", "shape_dist_traveled")),
            Map.entry("calendar_dates", List.of("service_id", "date", "exception_type", "exception_action", "source")),
            Map.entry("feed_agencies", List.of("agency_key", "agency_id", "agency_name", "agency_timezone")),
            Map.entry("service_calendar_summary", List.of(
                    "service_id",
                    "has_calendar",
                    "weekday_mask",
                    "start_date",
                    "end_date",
                    "addition_count",
                    "removal_count",
                    "trip_count",
                    "service_timezone",
                    "status",
                    "explanation"
            )),
            Map.entry("stop_search_tokens", List.of("token", "stop_id", "area_id")),
            Map.entry("stop_area_members", List.of("area_id", "stop_id")),
            Map.entry("transfers", List.of(
                    "transfer_id", "from_stop_id", "to_stop_id", "from_route_id", "to_route_id",
                    "from_trip_id", "to_trip_id", "transfer_type", "min_transfer_time", "service_id"
            )),
            Map.entry("stop_area_aliases", List.of("area_id", "alias_normalized", "alias_type", "priority")),
            Map.entry("area_route_service_summary", List.of("area_id", "route_id", "stop_time_count", "trip_count")),
            Map.entry("stop_area_cities", List.of("area_id", "city_name", "source", "quality", "data_version")),
            Map.entry("stop_area_profiles", List.of("area_id", "profile_class", "has_rail_service", "bus_only", "search_priority_score")),
            Map.entry("canonical_stop_areas", List.of("canonical_area_id", "canonical_display_name", "primary_stop_area_id", "display_quality")),
            Map.entry("canonical_stop_area_members", List.of(
                    "canonical_area_id",
                    "area_id",
                    "member_role",
                    "is_primary_for_search",
                    "is_primary_for_routing",
                    "is_visible_suggestion",
                    "access_cost_minutes",
                    "quality"
            )),
            Map.entry("canonical_stop_area_names", List.of("canonical_area_id", "original_name", "display_name", "display_name_normalized", "display_quality")),
            Map.entry("stop_area_display_names", List.of("area_id", "canonical_area_id", "public_display_name", "public_display_name_normalized", "public_city_name", "display_quality")),
            Map.entry("display_name_quality_findings", List.of(
                    "area_id",
                    "finding_type",
                    "classification",
                    "prefix",
                    "public_stop_name",
                    "public_city_name",
                    "public_display_name",
                    "action",
                    "rationale"
            )),
            Map.entry("canonical_stop_area_transfer_edges", List.of(
                    "canonical_area_id",
                    "from_area_id",
                    "to_area_id",
                    "min_transfer_minutes",
                    "quality",
                    "source"
            )),
            Map.entry("hub_profiles", List.of("area_id", "hub_level", "route_count", "trip_count", "transfer_candidate_score")),
            Map.entry("route_axes", List.of("axis_id", "route_id", "direction_id", "representative_trip_id", "trip_count", "stop_count")),
            Map.entry("route_axis_stops", List.of("axis_id", "sequence_index", "area_id")),
            Map.entry("transfer_rules", List.of(
                    "transfer_rule_id", "raw_transfer_id", "from_area_id", "to_area_id", "transfer_semantic",
                    "scope_type", "pedestrian_usable", "source", "confidence"
            )),
            Map.entry("transfer_edges", List.of(
                    "transfer_edge_id", "raw_transfer_id", "from_stop_area_id", "to_stop_area_id",
                    "min_transfer_seconds", "min_transfer_minutes", "is_traversable", "edge_kind",
                    "transfer_semantic", "scope_type", "distance_model", "quality", "source"
            )),
            Map.entry("stop_footpaths", List.of(
                    "footpath_id", "area_id", "from_stop_id", "to_stop_id", "distance_meters",
                    "min_transfer_seconds", "is_traversable", "quality", "distance_model", "time_model", "source"
            ))
    );

    private SqliteContract() {
    }

    public static List<String> expectedIndexesFor(String contractVersion, String runMode) {
        if (APP_RUNTIME_CONTRACT_VERSION.equals(contractVersion) && "APP_RUNTIME".equals(runMode)) {
            return APP_RUNTIME_EXPECTED_INDEXES;
        }
        return EXPECTED_INDEXES;
    }

    public static List<String> additiveIndexesFor(String contractVersion, String runMode) {
        if (APP_RUNTIME_CONTRACT_VERSION.equals(contractVersion) && "APP_RUNTIME".equals(runMode)) {
            return APP_RUNTIME_ADDITIVE_INDEXES;
        }
        return ADDITIVE_INDEXES;
    }
}
