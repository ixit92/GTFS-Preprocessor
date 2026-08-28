# IXIT GTFS Preprocessor

IXIT GTFS Preprocessor is a standalone Java 21 CLI that converts a static
GTFS ZIP into a validated, search- and routing-prepared SQLite database. It is
an offline data preparation tool. It does not contain a journey planner, an
Android integration, GTFS Realtime processing, or network access at runtime.

## Highlights

- streaming-oriented GTFS CSV parsing, including large `stop_times.txt` files;
- GTFS service times above `24:00:00`, stored as seconds since service-day
  start;
- stop-area derivation from `parent_station` while preserving original
  `stop_id` values;
- normalized stop names, search tokens, aliases, and explainable display-name
  transformations;
- combined `calendar.txt` and `calendar_dates.txt` service-day model;
- transfer rules, concrete stop footpaths, hub profiles, and optional route
  axes;
- SQLite metadata, indexes, schema validation, quality warnings, and JSON
  reports;
- feed fusion and read-only compatibility/audit commands;
- network-free self-tests executed by Maven.

The current producer metadata is:

- preprocessor version: `0.9.7`
- schema version: `0.1`
- contract version: `0.8`
- Java version: `21`

## Build

Requirements:

- JDK 21
- Maven 3.9 or newer

```bash
mvn clean package
```

The build runs the embedded `PreprocessorSelfTest` suite and creates a shaded
CLI JAR at:

```text
target/gtfs-preprocessor-0.9.7-SNAPSHOT.jar
```

## Quick Start

Place the GTFS ZIP in the gitignored `local-data/` directory, then run:

```bash
java -jar target/gtfs-preprocessor-0.9.7-SNAPSHOT.jar \
  --input local-data/feed.zip \
  --output build/ixit_gtfs.sqlite \
  --report-output build/ixit_gtfs_contract_report.json
```

`--output` and `--report-output` are intentionally restricted to the tool
directory. The default output is `build/ixit_gtfs.sqlite`.

For the complete derived schema used by a data consumer:

```bash
java -Xmx3g -jar target/gtfs-preprocessor-0.9.7-SNAPSHOT.jar \
  --input local-data/feed.zip \
  --output build/runtime.sqlite \
  --report-output build/runtime-report.json \
  --app-runtime
```

The `--app-runtime` name describes the output profile only. This repository
does not include or modify an application runtime.

## Required and Optional GTFS Files

Required:

- `stops.txt`
- `routes.txt`
- `trips.txt`
- `stop_times.txt`

Optional inputs include `transfers.txt`, `calendar.txt`, `calendar_dates.txt`,
`agency.txt`, `feed_info.txt`, and `shapes.txt`. Missing optional files produce
warnings where appropriate. A feed must still provide a usable service-day
model for date-based validation.

## Run Modes

- `full`: build the complete analytical schema.
- `core-only`: skip memory-intensive derived builders for diagnostics.
- `app-runtime`: build and validate the consumer-oriented schema while
  omitting route axes that are not required by that profile.

Select a mode with `--run-mode full|core-only|app-runtime`. The convenience
flags `--stress-core-only` and `--app-runtime` are also supported.

## Additional Commands

The shaded JAR also exposes:

- `fuse`: combine independently namespaced GTFS feeds;
- `service-day`: inspect whether a service runs on a given GTFS service date;
- `service-day-audit`: validate calendar behavior and overflow times;
- `routing-contract-poc`: inspect area-to-stop and trip/service relationships;
- `routing-contract-real-feed-audit`: run declarative compatibility scenarios;
- `feed-drift-audit` and `soak-compare`: compare immutable build reports;
- `cleanup-audit`: report or remove explicitly scoped audit artifacts;
- `mobile-package` and `mobile-update-contract`: build signed reduced data
  packages without embedding private keys.

Run the JAR with `--help` to print the complete command synopsis.

## Data Contract

Important identity and time rules:

- `stop_id` is the original, concrete GTFS stop ID.
- `area_id` is a derived station/stop-area identity.
- `stop_times` continues to reference concrete `stop_id` values.
- search tokens and hub profiles are descriptive aids, not routing decisions.
- transfer edges and footpaths are prepared evidence; a consumer still decides
  whether a connection is valid.
- `arrival_seconds` and `departure_seconds` are seconds since service-day
  start. Values greater than 86,400 are valid and are never reduced modulo 24.
- `calendar_dates` exceptions override the base `calendar` schedule.

See [docs/SQLITE_CONTRACT.md](docs/SQLITE_CONTRACT.md),
[docs/SERVICE_DAY_MODEL.md](docs/SERVICE_DAY_MODEL.md), and
[docs/TRANSFER_SEMANTICS.md](docs/TRANSFER_SEMANTICS.md).

## Data and Privacy

This repository contains no GTFS feed, generated SQLite database, municipality
dataset, signing key, server configuration, production report, or private Git
history. Users are responsible for obtaining input data and complying with its
license and attribution requirements.

## Project Status

This is a curated source snapshot of the isolated preprocessor. Contract `0.8`
remains an explicit compatibility boundary; downstream consumers must reject
unknown contract versions.

No open-source license has been selected yet. Until a `LICENSE` file is added,
the source is visible for evaluation but no permission to use, modify, or
redistribute it is granted. See [PUBLICATION_CHECKLIST.md](PUBLICATION_CHECKLIST.md).

