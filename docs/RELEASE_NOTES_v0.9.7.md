# IXIT GTFS Preprocessor v0.9.7

This is the first public binary release of the isolated IXIT GTFS
Preprocessor. It publishes the same producer contract already documented in
the source snapshot:

- preprocessor version `0.9.7`;
- SQLite schema version `0.1`;
- producer/consumer contract version `0.8`;
- Java 21 shaded CLI JAR;
- CycloneDX 1.6 JSON and XML software bills of materials;
- SHA-256 checksums for every published artifact.

## Release Hardening

- The complete network-free embedded verification suite is exposed as a
  regular JUnit test and is required by Maven Surefire.
- CI validates the release JAR and both SBOM files.
- CodeQL scans Java changes and the default branch weekly.
- Dependabot monitors Maven and GitHub Actions dependencies.
- Tag publication rebuilds and tests from source and rejects a tag that does
  not exactly match the Maven project version.
- The top-level `--help` command returns success for shell-friendly discovery.
- License, notice, CC BY 4.0, and third-party attribution files are embedded in
  the shaded JAR.

## Compatibility Boundary

Search tokens, hub profiles, route axes, transfer rules, and footpaths remain
prepared evidence. They do not make journey-planning decisions. Concrete trip
validation continues to use original GTFS `stop_id`, `trips`, `stop_times`,
`service_id`, and the service-day model. Times above `24:00:00` remain valid
seconds since service-day start.

No Android, app, core, routing, runtime-cache, server, feed, database, signing
key, or production configuration is included in this release.
