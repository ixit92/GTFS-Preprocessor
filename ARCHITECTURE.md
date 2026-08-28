# Architecture

## Pipeline

1. `GtfsPreprocessorCli` parses the command line and confines outputs to the
   repository directory.
2. `GtfsZipReader` and `GtfsCsvReader` read GTFS tables with RFC-style CSV
   quoting support.
3. The import model preserves original GTFS identities and converts service
   times to seconds since service-day start.
4. Builders derive stop areas, search data, service summaries, display names,
   transfer semantics, footpaths, hub profiles, and optional route axes.
5. `SqliteWriter` writes the core and derived schema in transactions and
   creates consumer indexes after bulk insertion.
6. Contract, service-day, display, transfer, and routing-compatibility audits
   verify the completed database.
7. A text report and machine-readable JSON report describe row counts,
   metadata, quality warnings, timings, and validation results.

## Identity Boundaries

The producer deliberately keeps two identity levels:

- `stop_id` identifies a concrete GTFS boarding location.
- `area_id` groups stops for station-oriented search and selection.

Area membership does not prove that two concrete stops are connected by a
zero-distance walk. Transfer rules and concrete footpaths retain their own
scope and evidence.

## Time Model

GTFS time is service-day local time, not an instant and not a modulo-24 wall
clock. `25:10:00` is stored as `90,600` seconds. Calendar exceptions are
resolved before a trip is considered active for a service date.

## Memory Model

Large row sets are streamed or staged in SQLite where practical. Derived
builders use explicit heap release points and report memory and throughput
diagnostics. Real-feed sizing still depends on feed shape; use an explicit JVM
heap limit and monitor disk space for national-scale feeds.

## Out of Scope

- journey selection or routing decisions;
- application or mobile runtime integration;
- alarm or wake-time calculation;
- GTFS Realtime ingestion;
- downloading or redistributing GTFS feeds;
- automatic correction of source data.

