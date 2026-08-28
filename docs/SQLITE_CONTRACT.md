# IXIT GTFS SQLite Contract v0.1

Status: aktive Schema-Referenz; konkrete Runtime-Metadaten haben Vorrang

Contract name: `IXIT_GTFS_SQLITE_CONTRACT`

Contract version: `0.8`

Schema version: `0.1`

Preprocessor version: `0.9.7`

## Purpose

This document describes the SQLite data contract produced by the isolated IXIT GTFS Preprocessor. It is a compatibility boundary for later IXIT routing work. It does not integrate the preprocessor with the app, Android runtime, core logic, routing logic, wake-time calculation, or GTFS-Realtime.

## Identity Policies

`stop_id` remains the original GTFS stop ID. It is not rewritten by IXIT.

`area_id` is an IXIT-derived StopArea ID. It is currently derived from `parent_station` when a stop has one; otherwise the stop's own `stop_id` is used.

`parent_station` keeps the original GTFS relationship from `stops.txt`. IXIT uses it only to derive StopAreas and membership rows.

`stop_times` continues to reference concrete `stop_id` values. Future routing for concrete trips should use `stop_id`, not `area_id`.

User-facing search can prefer `area_id` later because passengers usually search for stations or station groups, not individual platforms.

Search tokens are search and UI helpers. They are not routing decisions and must not be interpreted as route quality, transfer quality, or travel feasibility.

## Time Model

`arrival_seconds` and `departure_seconds` are seconds since the start of the GTFS service day.

GTFS times over `24:00:00` are allowed and correct. For example:

- `24:30:00` is stored as `88200`
- `25:10:00` is stored as `90600`

The original GTFS time text is not currently stored in v0.1.

## Raw GTFS vs IXIT-Derived Data

Raw GTFS-derived tables keep source IDs and source semantics as far as possible:

- `stops`
- `routes`
- `trips`
- `stop_times`
- `transfers`
- `calendar`
- `calendar_dates`
- `feed_agencies`
- `service_calendar_summary`

IXIT-derived tables and columns prepare faster lookup, station grouping, reporting, and compatibility:

- `stop_areas`
- `stop_area_members`
- `stop_name_normalized`
- `area_name_normalized`
- `stop_search_tokens`
- `stop_area_cities`
- `stop_area_display_names`
- `display_name_quality_findings`
- `hub_profiles`
- `route_axes`
- `route_axis_stops`
- `transfer_rules`
- `transfer_edges`
- `stop_footpaths`
- `ixit_metadata`

Derived data must remain explainable from the GTFS source data and the documented IXIT policies above.

## Tables

### `ixit_metadata`

Stores schema and preprocessor metadata.

Columns:

- `key TEXT PRIMARY KEY`
- `value TEXT NOT NULL`

Required keys:

- `schema_version = 0.1`
- `preprocessor_version = 0.9.7`
- `generated_at`
- `contract_name = IXIT_GTFS_SQLITE_CONTRACT`
- `contract_version = 0.8`
- `time_model = seconds_since_service_day_start`
- `stop_id_policy = original_gtfs_stop_id`
- `area_id_policy = parent_station_or_stop_id`
- `search_tokens_policy = search_only_not_routing`
- `transfer_semantics_policy = preserve_gtfs_scope_and_separate_non_walking`
- `footpath_policy = concrete_stop_estimates_never_area_membership_as_zero_distance`
- `display_name_transformation_version = 2`
- `display_name_transformation_policy = explainable_search_ui_only`
- `service_day_model_version = 1`
- `service_day_resolution_policy = calendar_dates_override_calendar`
- `service_day_timezone_policy = gtfs_service_date_in_agency_timezone`
- `service_day_time_overflow_policy = preserve_seconds_since_service_day_start`
- `feed_timezones` as a sorted comma-separated list or `unknown`
- `build_identity_version = 1`
- `build_identity_sha256` as 64 lowercase hexadecimal characters
- `source_gtfs_sha256` as 64 lowercase hexadecimal characters
- `preprocessor_artifact_sha256` as 64 lowercase hexadecimal characters
- `preprocessor_artifact_kind = JAR` or `CLASSES_DIRECTORY`
- `municipality_data_sha256` as SHA-256 or `not_provided`
- `run_mode = FULL`, `CORE_ONLY`, or `APP_RUNTIME`

Optional source keys:

- `source_feed_name`
- `source_feed_version`

The build identity binds the database to the exact GTFS input, running
preprocessor artifact, run mode, contract and optional municipality dataset.
Changing any of these inputs produces a different `build_identity_sha256`.
`generated_at` is deliberately excluded so identical inputs remain comparable.

For trip-bearing services, `service_calendar_summary.service_timezone` must be
a zone identifier available from the IANA/TZDB data used by Java. The sentinel
values `UNKNOWN` and `MULTIPLE` are diagnostics, not usable routing timezones.
The v0.8.1 app-ready audit rejects invalid or unresolved trip-service zones.

The isolated v0.8 Routing Contract Consumer PoC accepts contract `0.7` only.
It resolves user-facing `area_id` selections to concrete `stop_id` members and
then validates trips through `trips`, ordered `stop_times`, and the combined
service-day model. It does not change this SQLite contract or integrate it into
the application runtime.

### `stops`

Source table based on GTFS `stops.txt`, with one IXIT normalized-name helper column.

Columns:

- `stop_id TEXT PRIMARY KEY`
- `stop_code TEXT`
- `stop_name TEXT`
- `stop_name_normalized TEXT`
- `stop_lat REAL`
- `stop_lon REAL`
- `parent_station TEXT`
- `location_type INTEGER`
- `platform_code TEXT`

Meaning:

- `stop_id` is the original GTFS stop ID.
- `stop_name` is the original GTFS name.
- `stop_name_normalized` is search/index data only.
- `parent_station` is the original GTFS parent station reference.

Index:

- `idx_stops_parent_station ON stops(parent_station)`

### `stop_areas`

IXIT-derived station grouping table.

Columns:

- `area_id TEXT PRIMARY KEY`
- `area_name TEXT`
- `area_name_normalized TEXT`
- `area_lat REAL`
- `area_lon REAL`
- `stop_count INTEGER NOT NULL`

Meaning:

- `area_id` is derived from `parent_station` or `stop_id`.
- `area_name` is taken from the parent station when possible, otherwise from the first member stop.
- `area_name_normalized` is search/index data only.
- `stop_count` is the number of member stops in the area.

### `stop_area_members`

IXIT-derived mapping between StopAreas and concrete GTFS stops.

Columns:

- `area_id TEXT NOT NULL`
- `stop_id TEXT NOT NULL`
- `member_role TEXT NOT NULL`
- `PRIMARY KEY (area_id, stop_id)`

Meaning:

- `area_id` references an IXIT StopArea.
- `stop_id` references the original GTFS stop.
- `member_role` is a descriptive helper, currently `AREA_ANCHOR` or `STOP`.

Indexes:

- `idx_stop_area_members_area_id ON stop_area_members(area_id)`
- `idx_stop_area_members_stop_id ON stop_area_members(stop_id)`

### `stop_area_cities` (additiv)

Materialisierte, fuer Anzeige und Diagnose bestimmte Gemeindezuordnung einer
StopArea. Routing darf diese Tabelle nicht als Erreichbarkeitsbeweis verwenden.

Columns:

- `area_id TEXT PRIMARY KEY`
- `municipality_id TEXT`
- `city_name TEXT`
- `municipality_type TEXT`
- `source TEXT NOT NULL`
- `quality TEXT NOT NULL`
- `data_version TEXT`
- `explanation TEXT`

Aktuelle Quellen und Qualitaeten:

- `BKG_VG250_GEOMETRY` / `OFFICIAL_BOUNDARY`: Punkt-in-Polygon gegen einen
  explizit versionierten amtlichen Gemeindedatensatz.
- `GTFS_NAME_FALLBACK` / `INFERRED`: nur bei einer sicheren Bahnhof-/Kommaform.
- `UNRESOLVED` / `UNRESOLVED`: keine belastbare Gemeinde gefunden.

Indexes:

- `idx_stop_area_cities_city ON stop_area_cities(city_name)`
- `idx_stop_area_cities_municipality ON stop_area_cities(municipality_id)`
- `idx_stop_area_cities_quality ON stop_area_cities(quality)`

### `stop_area_display_names` (additiv)

Materialisierte Anzeige im Format `Haltestelle, Stadtname`. Sie ist eine
Such-/UI-Hilfe und keine Routingentscheidung. Rohnamen in `stops` und
`stop_areas` bleiben unveraendert.

Columns:

- `area_id TEXT PRIMARY KEY`
- `canonical_area_id TEXT NOT NULL`
- `public_display_name TEXT NOT NULL`
- `public_display_name_normalized TEXT NOT NULL`
- `public_stop_name TEXT`
- `public_city_name TEXT`
- `display_quality TEXT NOT NULL`
- `source TEXT NOT NULL`
- `explanation TEXT`

Seit Preprocessor v0.9.5 enthaelt `explanation` ein maschinenlesbares
`rules=<RULE>|<RULE>`-Feld sowie `output=<public_display_name>`. `NONE` ist nur
zulaessig, wenn keine Transformation angewandt wurde. Die kanonisch geordneten
Regel-IDs sind:

- `CITY_FROM_RESOLVED_CONTEXT`
- `CITY_CODE_EXPANDED`
- `CITY_PREFIX_REMOVED`
- `CITY_QUALIFIER_REMOVED`
- `MODE_PREFIX_REMOVED`
- `TECHNICAL_QUALIFIER_REMOVED`
- `STATION_ABBREVIATION_EXPANDED`
- `GENERIC_STATION_SUFFIX_REMOVED`
- `STREET_SUFFIX_NORMALIZED`
- `LOCALITY_COMPOUND_PRESERVED`
- `STOP_CITY_COMPOSED`

Unbekannte, doppelte, leere oder falsch geordnete Regel-IDs sind ein
Display-Audit-Fehler. Die Regeln erklaeren ausschliesslich Anzeigeableitungen;
sie duerfen weder StopArea-Mitgliedschaft noch Fahrten oder Transfers
veraendern.

Indexes:

- `idx_stop_area_display_names_canonical`
- `idx_stop_area_display_names_public`
- `idx_stop_area_display_names_normalized`
- `idx_stop_area_display_names_city`

### `routes`

Source table based on GTFS `routes.txt`.

Columns:

- `route_id TEXT PRIMARY KEY`
- `agency_id TEXT`
- `route_short_name TEXT`
- `route_long_name TEXT`
- `route_type INTEGER`

### `trips`

Source table based on GTFS `trips.txt`.

Columns:

- `trip_id TEXT PRIMARY KEY`
- `route_id TEXT NOT NULL`
- `service_id TEXT NOT NULL`
- `trip_headsign TEXT`
- `direction_id TEXT`
- `block_id TEXT`

Indexes:

- `idx_trips_route_id ON trips(route_id)`
- `idx_trips_service_id ON trips(service_id)`

### `stop_times`

Source table based on GTFS `stop_times.txt`, with GTFS times converted to seconds.

Columns:

- `trip_id TEXT NOT NULL`
- `arrival_seconds INTEGER NOT NULL`
- `departure_seconds INTEGER NOT NULL`
- `stop_id TEXT NOT NULL`
- `stop_sequence INTEGER NOT NULL`
- `pickup_type INTEGER`
- `drop_off_type INTEGER`

Meaning:

- `stop_id` points to a concrete original GTFS stop.
- `arrival_seconds` and `departure_seconds` use the documented time model.

Indexes:

- `idx_stop_times_trip_sequence ON stop_times(trip_id, stop_sequence)`
- `idx_stop_times_stop_departure ON stop_times(stop_id, departure_seconds)`

### `transfers`

Source table based on optional GTFS `transfers.txt`.

Columns:

- `transfer_id INTEGER PRIMARY KEY`
- `from_stop_id TEXT NOT NULL`
- `to_stop_id TEXT NOT NULL`
- `from_route_id TEXT`
- `to_route_id TEXT`
- `from_trip_id TEXT`
- `to_trip_id TEXT`
- `transfer_type INTEGER`
- `min_transfer_time INTEGER`
- `service_id TEXT`

Meaning:

- `transfer_id` is a stable row identifier within the generated artifact.
- Route, trip, and service fields preserve the optional GTFS transfer scope.
- `transfer_type` keeps the raw GTFS value. In particular, type 3 is prohibited,
  type 4 is an in-seat transfer, and type 5 forbids an in-seat transfer.
- A raw transfer row is not automatically a pedestrian edge.

Indexes:

- `idx_transfers_from_to ON transfers(from_stop_id, to_stop_id)`
- `idx_transfers_type ON transfers(transfer_type)`
- `idx_transfers_trip_scope ON transfers(from_trip_id, to_trip_id, service_id)`

### `calendar`

Source table based on optional GTFS `calendar.txt`.

Columns:

- `service_id TEXT PRIMARY KEY`
- `monday INTEGER`
- `tuesday INTEGER`
- `wednesday INTEGER`
- `thursday INTEGER`
- `friday INTEGER`
- `saturday INTEGER`
- `sunday INTEGER`
- `start_date TEXT`
- `end_date TEXT`

Index:

- `idx_calendar_service_id ON calendar(service_id)`

### `calendar_dates`

Source table based on optional GTFS `calendar_dates.txt`.

Columns:

- `service_id TEXT NOT NULL`
- `date TEXT NOT NULL`
- `exception_type INTEGER`
- `exception_action TEXT NOT NULL`
- `source TEXT NOT NULL`
- `PRIMARY KEY (service_id, date)`

Meaning:

- `service_id` references a GTFS service ID from `trips.service_id` and optionally `calendar.service_id`.
- `date` is the GTFS service exception date in `YYYYMMDD` text format.
- `exception_type` keeps the GTFS meaning: `1` adds service for the date, `2` removes service for the date.
- `exception_action` makes the same meaning explicit as `ADDITION`, `REMOVAL`, or `UNKNOWN` for invalid source values.
- `source` is `GTFS_CALENDAR_DATES`.
- If `calendar_dates.txt` is absent, the table still exists with zero rows.

Indexes:

- `idx_calendar_dates_service_id ON calendar_dates(service_id)`
- `idx_calendar_dates_date ON calendar_dates(date)`
- `idx_calendar_dates_service_date ON calendar_dates(service_id, date)`

### `feed_agencies`

Preserves the GTFS agency identity, name and `agency_timezone`. The synthetic
`agency_key` only provides a stable primary key when a single-agency feed omits
`agency_id`; it does not replace GTFS route agency references.

### `service_calendar_summary`

IXIT-derived service-day preparation keyed by original GTFS `service_id`.

Columns:

- `service_id TEXT PRIMARY KEY`
- `has_calendar INTEGER NOT NULL`
- `weekday_mask INTEGER NOT NULL`
- `start_date TEXT`
- `end_date TEXT`
- `addition_count INTEGER NOT NULL`
- `removal_count INTEGER NOT NULL`
- `first_exception_date TEXT`
- `last_exception_date TEXT`
- `trip_count INTEGER NOT NULL`
- `service_timezone TEXT NOT NULL`
- `status TEXT NOT NULL`
- `explanation TEXT NOT NULL`

`weekday_mask` uses bits Monday=1 through Sunday=64. `status` is
`BASE_WITH_EXCEPTIONS`, `BASE_ONLY`, `EXCEPTIONS_ONLY`, or `UNRESOLVED`.
`trip_count = 0` identifies definitions not referenced by trips without
discarding them. `calendar_dates` always overrides the base weekly calendar.

Indexes:

- `idx_service_calendar_status`
- `idx_service_calendar_timezone`
- `idx_service_calendar_trip_count`

### `stop_search_tokens`

IXIT-derived search helper table.

Columns:

- `stop_id TEXT`
- `area_id TEXT NOT NULL`
- `token TEXT NOT NULL`
- `token_type TEXT NOT NULL`
- `source TEXT NOT NULL`

Meaning:

- Stop-level tokens contain both `stop_id` and `area_id`.
- Area-level tokens use an empty `stop_id` and the relevant `area_id`.
- `token` is normalized search text.
- `token_type` is currently one of `NAME`, `SYNONYM`, `NORMALIZED`, `AREA_NAME`.
- `source` describes where the token came from, for example `STOP_NAME` or `AREA_NAME`.

Indexes:

- `idx_stop_search_tokens_token ON stop_search_tokens(token)`
- `idx_stop_search_tokens_stop_id ON stop_search_tokens(stop_id)`
- `idx_stop_search_tokens_area_id ON stop_search_tokens(area_id)`
- `idx_stop_search_tokens_token_area ON stop_search_tokens(token, area_id)`

### `display_name_quality_findings` (additive)

Non-destructive review baseline for public stop names. It classifies every
remaining uppercase prefix detected by the display audit and every public stop
name equal to its municipality. It never changes raw GTFS names or public
display names.

Columns:

- `area_id TEXT NOT NULL`
- `finding_type TEXT NOT NULL`
- `classification TEXT NOT NULL`
- `prefix TEXT`
- `public_stop_name TEXT NOT NULL`
- `public_city_name TEXT NOT NULL`
- `public_display_name TEXT NOT NULL`
- `action TEXT NOT NULL`
- `rationale TEXT NOT NULL`
- `PRIMARY KEY (area_id, finding_type)`

`finding_type` is `UPPERCASE_PREFIX` or `MUNICIPALITY_ONLY`. v0.6.5 permits
only `action = PRESERVE`; a different action is a quality-contract failure.
Classifications distinguish institutions, named entities, transport terms,
possible locality codes, ambiguous acronyms, section labels and locality-only
stop names. In particular, `UNIL` and `HEIG` are institution names and `ZUP`
is a transport/infrastructure term, not a city prefix.

Indexes:

- `idx_display_name_quality_type`
- `idx_display_name_quality_classification`
- `idx_display_name_quality_prefix`
- `idx_display_name_quality_area`

### `hub_profiles`

IXIT-derived analysis table for StopArea hub preparation.

HubProfiles describe the transport relevance of a StopArea. They are explainable, rule-based preparation data. They are not routing decisions, connection decisions, transfer decisions, wake-time decisions, or GTFS-Realtime data.

Columns:

- `area_id TEXT PRIMARY KEY`
- `hub_level TEXT NOT NULL`
- `stop_count INTEGER NOT NULL`
- `route_count INTEGER NOT NULL`
- `trip_count INTEGER NOT NULL`
- `route_type_count INTEGER NOT NULL`
- `stop_time_count INTEGER NOT NULL`
- `has_train INTEGER NOT NULL`
- `has_subway INTEGER NOT NULL`
- `has_tram INTEGER NOT NULL`
- `has_bus INTEGER NOT NULL`
- `has_rail_keyword INTEGER NOT NULL`
- `has_main_station_keyword INTEGER NOT NULL`
- `transfer_candidate_score INTEGER NOT NULL`
- `explanation TEXT`

Meaning:

- `area_id` references the IXIT-derived StopArea.
- `hub_level` is a first rule-based classification.
- `stop_count` is the number of concrete GTFS stops in the StopArea.
- `route_count` counts distinct routes observed through `stop_times`.
- `trip_count` counts distinct trips observed through `stop_times`.
- `route_type_count` counts distinct GTFS `route_type` values observed through the area.
- `stop_time_count` counts stop_time rows at member stops of the area.
- `has_train`, `has_subway`, `has_tram`, and `has_bus` summarize observed route types.
- `has_rail_keyword` and `has_main_station_keyword` come from normalized Stop/StopArea names.
- `transfer_candidate_score` is a transparent heuristic score for analysis and prioritization.
- `explanation` records why the rule-based classification was assigned.

Current `hub_level` values:

- `NONE`: no clear hub signal
- `SMALL`: more than one route, multiple stops, transfer references, or small positive score
- `MEDIUM`: several routes, many trips, or mixed route types with transfer relevance
- `LARGE`: many routes, many trips, several route types, or high score
- `MAIN_STATION_CANDIDATE`: main-station keyword or very strong hub indicators

Routing may later use HubProfiles as supporting data only. Routing must still decide concrete trips and stops through routing-specific logic and `stop_id`-based schedule data.

Indexes:

- `idx_hub_profiles_hub_level ON hub_profiles(hub_level)`
- `idx_hub_profiles_route_count ON hub_profiles(route_count)`
- `idx_hub_profiles_trip_count ON hub_profiles(trip_count)`
- `idx_hub_profiles_transfer_candidate_score ON hub_profiles(transfer_candidate_score)`

### `route_axes`

IXIT-derived table for route or line axis preparation.

RouteAxis describes common StopArea sequences for trips of one route and direction. It is an analysis and structure dataset. It is not a routing decision and must not be used to blindly derive a connection.

Rows are grouped by:

- exact same StopArea sequence
- same `route_id`
- same `direction_id`

Columns:

- `axis_id TEXT PRIMARY KEY`
- `route_id TEXT NOT NULL`
- `direction_id TEXT`
- `representative_trip_id TEXT NOT NULL`
- `trip_count INTEGER NOT NULL`
- `stop_count INTEGER NOT NULL`
- `first_area_id TEXT`
- `last_area_id TEXT`
- `route_short_name TEXT`
- `route_long_name TEXT`
- `route_type INTEGER`
- `explanation TEXT`

Meaning:

- `axis_id` is an IXIT-generated stable identifier for one grouped axis.
- `route_id` remains the original GTFS route ID.
- `direction_id` comes from GTFS `trips.txt`.
- `representative_trip_id` is one concrete GTFS trip from the grouped trips.
- `trip_count` is the number of trips represented by this axis.
- `stop_count` is the number of StopAreas in the deduplicated sequence.
- `first_area_id` and `last_area_id` are the first and last StopAreas in the axis sequence.
- `route_short_name`, `route_long_name`, and `route_type` are copied route metadata for easier inspection.
- `explanation` documents the grouping rule.

Indexes:

- `idx_route_axes_route_id ON route_axes(route_id)`
- `idx_route_axes_direction_id ON route_axes(direction_id)`
- `idx_route_axes_route_direction ON route_axes(route_id, direction_id)`
- `idx_route_axes_first_area_id ON route_axes(first_area_id)`
- `idx_route_axes_last_area_id ON route_axes(last_area_id)`

### `route_axis_stops`

IXIT-derived ordered StopArea sequence for each RouteAxis.

Columns:

- `axis_id TEXT NOT NULL`
- `sequence_index INTEGER NOT NULL`
- `area_id TEXT NOT NULL`
- `PRIMARY KEY (axis_id, sequence_index)`

Meaning:

- `axis_id` references `route_axes.axis_id`.
- `sequence_index` is zero-based sequence order.
- `area_id` references the IXIT-derived StopArea.

When multiple consecutive `stop_times` entries map to the same StopArea, only one sequence entry is kept. For example, platform stops `A_1`, `A_2`, `B`, `C` become StopArea sequence `A`, `B`, `C`.

Indexes:

- `idx_route_axis_stops_area_id ON route_axis_stops(area_id)`
- `idx_route_axis_stops_axis_id ON route_axis_stops(axis_id)`

Routing may later use RouteAxis as supporting data for diagnostics, candidate explanation, or preselection. Routing must still use schedule and stop-level data to decide actual connections.

### `transfer_rules`

IXIT-derived helper table for transfer preparation.

TransferRules describe possible or recommended transfer relationships between StopAreas and optionally concrete stops. They are helper data only. They are not routing decisions, connection decisions, wake-time decisions, walking-time calculations, or GTFS-Realtime data.

Columns:

- `transfer_rule_id TEXT PRIMARY KEY`
- `raw_transfer_id INTEGER`
- `from_area_id TEXT NOT NULL`
- `to_area_id TEXT NOT NULL`
- `from_stop_id TEXT`
- `to_stop_id TEXT`
- `transfer_type INTEGER`
- `min_transfer_time_seconds INTEGER`
- `transfer_semantic TEXT NOT NULL`
- `scope_type TEXT NOT NULL`
- `pedestrian_usable INTEGER NOT NULL`
- `source TEXT NOT NULL`
- `confidence TEXT NOT NULL`
- `explanation TEXT`

Meaning:

- `transfer_rule_id` is an IXIT-generated identifier.
- `raw_transfer_id` references the preserved row in `transfers` when applicable.
- `from_area_id` and `to_area_id` reference IXIT-derived StopAreas.
- `from_stop_id` and `to_stop_id` preserve concrete GTFS stops when the rule came from `transfers.txt`.
- `transfer_type` preserves GTFS `transfer_type` when present.
- `min_transfer_time_seconds` preserves GTFS `min_transfer_time` when present. A
  generated area-membership rule does not invent a transfer duration.
- `transfer_semantic` classifies the raw GTFS meaning as `RECOMMENDED`, `TIMED`,
  `MINIMUM_TIME`, `PROHIBITED`, `IN_SEAT_ALLOWED`, `IN_SEAT_FORBIDDEN`, or `UNKNOWN`.
- `scope_type` is `STOP`, `ROUTE`, `TRIP`, `TRIP_SERVICE`, or `SERVICE`.
- `pedestrian_usable = 1` only marks an unscoped stop-level type 0 or type 2 row
  as eligible for a pedestrian edge. It is still not a complete routing decision.
- `source` explains where the rule came from.
- `confidence` communicates how strong the rule source is.
- `explanation` documents the generation rule.

Current `source` values:

- `GTFS_TRANSFERS`: mapped from raw GTFS `transfers.txt`
- `SAME_STOP_AREA`: conservative generated rule for Stops in the same StopArea
- `GENERATED_PLATFORM_TRANSFER`: reserved for future explicit platform generation
- `GENERATED_NEARBY_AREA`: reserved for future conservative nearby-area generation

Current `confidence` values:

- `HIGH`: GTFS-provided transfer
- `LOW`: same-StopArea membership or another generated rule without a surveyed path

Raw GTFS `transfers` and IXIT `transfer_rules` are intentionally separate:

- `transfers` keeps source rows from GTFS.
- `transfer_rules` maps or derives those rows into StopArea-oriented helper data.

Routing must later validate TransferRules against concrete connections, the time model, StopTimes, and routing-specific constraints. Routing must not blindly accept a TransferRule as a valid itinerary transfer.

Indexes:

- `idx_transfer_rules_from_area_id ON transfer_rules(from_area_id)`
- `idx_transfer_rules_to_area_id ON transfer_rules(to_area_id)`
- `idx_transfer_rules_from_to_area ON transfer_rules(from_area_id, to_area_id)`
- `idx_transfer_rules_source ON transfer_rules(source)`
- `idx_transfer_rules_confidence ON transfer_rules(confidence)`
- `idx_transfer_rules_semantic ON transfer_rules(transfer_semantic)`
- `idx_transfer_rules_scope ON transfer_rules(scope_type)`
- `idx_transfer_rules_pedestrian ON transfer_rules(pedestrian_usable)`
- `idx_transfer_rules_raw ON transfer_rules(raw_transfer_id)`

### `transfer_edges` (additive)

Prepared area-level edges and diagnostic candidates. An entry is usable by a
future routing consumer only when `is_traversable = 1`; all other entries are
explanation or discovery data.

Columns:

- `transfer_edge_id TEXT PRIMARY KEY`
- `raw_transfer_id INTEGER`
- `from_stop_area_id TEXT NOT NULL`
- `to_stop_area_id TEXT NOT NULL`
- `from_stop_id TEXT`
- `to_stop_id TEXT`
- `distance_meters INTEGER`
- `min_transfer_seconds INTEGER NOT NULL`
- `min_transfer_minutes INTEGER NOT NULL`
- `is_traversable INTEGER NOT NULL`
- `edge_kind TEXT NOT NULL`
- `transfer_semantic TEXT NOT NULL`
- `scope_type TEXT NOT NULL`
- `distance_model TEXT NOT NULL`
- `quality TEXT NOT NULL`
- `source TEXT NOT NULL`
- `explanation TEXT`

Safety rules:

- Only unscoped type 0 or type 2 GTFS rows may create traversable GTFS edges.
- Types 3, 4, and 5 never create pedestrian edges.
- Route-, trip-, or service-scoped rows remain rules and never become generic edges.
- Same-area and distance-heuristic edges are non-traversable candidates.
- `distance_meters` from coordinates is a straight-line lower bound, not a surveyed path.

Indexes include area lookups plus `is_traversable`, `edge_kind`, `raw_transfer_id`,
`quality`, and `source`.

### `stop_footpaths`

Concrete directed footpath estimates between boarding stops in one StopArea.
These rows preserve `stop_id` granularity; they do not collapse a station to an
area-level zero-distance transfer.

Columns:

- `footpath_id TEXT PRIMARY KEY`
- `area_id TEXT NOT NULL`
- `from_stop_id TEXT NOT NULL`
- `to_stop_id TEXT NOT NULL`
- `distance_meters INTEGER`
- `min_transfer_seconds INTEGER`
- `is_traversable INTEGER NOT NULL`
- `quality TEXT NOT NULL`
- `distance_model TEXT NOT NULL`
- `time_model TEXT NOT NULL`
- `source TEXT NOT NULL`
- `explanation TEXT`
- `UNIQUE (area_id, from_stop_id, to_stop_id)`

Coordinate-based paths use a straight-line distance and the documented model
`detour_1.35_speed_1.2mps_plus_60s_min_120s`. Estimates up to 400 meters may be
marked traversable. Longer, missing-coordinate, or otherwise uncertain pairs
remain `UNKNOWN` and non-traversable. GTFS `pathways.txt` would be a stronger
source, but it is not synthesized when absent.

Indexes support area, source stop, target stop, traversability, and quality lookups.

## Contract Validation

The preprocessor validates the SQLite file after writing it. Validation fails the CLI run if:

- an expected table is missing
- `ixit_metadata` is missing
- required metadata values are missing or unexpected
- an expected index is missing
- required columns are missing from `stop_times`, `stop_search_tokens`, or `stop_area_members`
- required columns are missing from `hub_profiles`
- required columns are missing from `route_axes` or `route_axis_stops`
- required columns are missing from `transfers`, `transfer_rules`, `transfer_edges`, or `stop_footpaths`
- required columns are missing from `display_name_quality_findings`
- the display quality baseline has coverage gaps or destructive actions
- non-pedestrian or scoped GTFS transfers leaked into traversable edges
- heuristic or area-membership candidates are traversable
- implausible stop footpaths are marked traversable

Validation failures are contract breaks and should be treated as build or preprocessing failures.

## JSON Contract Report

The preprocessor writes `ixit_gtfs_contract_report.json` next to the generated SQLite file.

It contains:

- schema version
- preprocessor version
- contract name and version
- expected tables
- expected indexes
- row counts
- warnings
- time model
- ID/search policies
- transfer-semantics and footpath policies
- transfer/footpath audit results and safety violations
- `app_ready_sqlite.display_name_audit` with a full scan of public display
names, residual city prefixes, format mismatches, matching city qualifiers,
  municipality-only names, suspicious unknown prefixes and bounded samples
- `app_ready_sqlite.display_name_quality_baseline` with classification counts,
  coverage gaps, destructive-action count and bounded samples

For `APP_RUNTIME`, known city-prefix residue, a duplicate city-name prefix, a
matching city qualifier or a `public_stop_name, public_city_name` format break
makes the output not app-ready. Suspicious unknown prefixes remain diagnostic
because they may be legitimate institution or district names. A stop whose
complete name equals its municipality is also diagnostic because no safe,
non-invented station component can be derived.
