# IXIT GTFS Preprocessor

[![CI](https://github.com/ixit92/GTFS-Preprocessor/actions/workflows/ci.yml/badge.svg)](https://github.com/ixit92/GTFS-Preprocessor/actions/workflows/ci.yml)
[![CodeQL](https://github.com/ixit92/GTFS-Preprocessor/actions/workflows/codeql.yml/badge.svg)](https://github.com/ixit92/GTFS-Preprocessor/actions/workflows/codeql.yml)

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
- directed station walks from `pathways.txt`, with separate walking time,
  one transfer buffer, GTFS minimum times, and replayable pathway provenance;
- SQLite metadata, indexes, schema validation, quality warnings, and JSON
  reports;
- feed fusion and read-only compatibility/audit commands;
- network-free self-tests executed by Maven.

The current producer metadata is:

- preprocessor version: `0.9.9-SNAPSHOT` (development)
- schema version: `0.1`
- contract version: `0.9`
- Java version: `21`

## Build

Requirements:

- JDK 21
- no system Maven installation is required; the wrapper uses Maven `3.9.16`

```bash
./mvnw clean package
```

The build runs the embedded `PreprocessorSelfTest` suite through JUnit and
creates a shaded CLI JAR plus CycloneDX 1.6 SBOMs at:

```text
target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar
target/gtfs-preprocessor-0.9.9-SNAPSHOT-sbom.json
target/gtfs-preprocessor-0.9.9-SNAPSHOT-sbom.xml
```

## Quick Start

Place the GTFS ZIP in the gitignored `local-data/` directory, then run:

```bash
java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar \
  --input local-data/feed.zip \
  --output build/ixit_gtfs.sqlite \
  --report-output build/ixit_gtfs_contract_report.json
```

`--output` and `--report-output` are intentionally restricted to the tool
directory. The default output is `build/ixit_gtfs.sqlite`.

For the complete derived schema used by a data consumer:

```bash
java -Xmx3g -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar \
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
`agency.txt`, `feed_info.txt`, `shapes.txt`, and `pathways.txt`. Missing optional files produce
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

## Release Verification

GitHub releases contain the shaded JAR, CycloneDX JSON and XML SBOMs, and a
`SHA256SUMS` file. Verify downloaded artifacts on Linux with:

```bash
sha256sum --check SHA256SUMS
```

Release tags must exactly match the Maven version. The release workflow rebuilds
and tests the project from the tagged source before publishing any artifact.
The JAR carries the project license, notice, CC BY 4.0 text, and third-party
attribution under `META-INF/`.

Release builds must also reproduce the JAR and both SBOMs byte-for-byte. GitHub
build-provenance attestations cover every published asset and can be checked
with:

```bash
gh attestation verify gtfs-preprocessor-0.9.8.jar \
  --repo ixit92/GTFS-Preprocessor
```

See [docs/REPRODUCIBLE_BUILDS.md](docs/REPRODUCIBLE_BUILDS.md) for the pinned
toolchain and local verification procedure.

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
[docs/WALK_TRANSFERS.md](docs/WALK_TRANSFERS.md). The preceding contract's
transfer audit is retained in [docs/TRANSFER_SEMANTICS.md](docs/TRANSFER_SEMANTICS.md).

For an isolated baseline/candidate comparison, see
[docs/WALK_REAL_FEED_AUDIT.md](docs/WALK_REAL_FEED_AUDIT.md).
RouteAxis allocation hardening and its isolated comparison are described in
[docs/ROUTE_AXIS_HEADROOM.md](docs/ROUTE_AXIS_HEADROOM.md); downstream acceptance
remains a [separate Contract 0.9 gate](docs/CONSUMER_CONTRACT_09_GATE.md).

## Data and Privacy

This repository contains no GTFS feed, generated SQLite database, municipality
dataset, signing key, server configuration, production report, or private Git
history. Users are responsible for obtaining input data and complying with its
license and attribution requirements.

## Project Status

This is a curated source snapshot of the isolated preprocessor. Contract `0.9`
remains an explicit compatibility boundary; downstream consumers must reject
unknown contract versions.

## License

Java source code and build configuration are licensed under the
[Apache License 2.0](LICENSE). Markdown documentation and bundled route-color
CSV data are licensed under
[Creative Commons Attribution 4.0 International](LICENSES/CC-BY-4.0.md).
Third-party attribution is listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
