# IXIT GTFS Preprocessor v0.9.8

This maintenance release hardens how public artifacts are built and verified.
The SQLite schema remains `0.1` and the producer/consumer contract remains
`0.8`.

## Build And Supply Chain

- Maven `3.9.16` is pinned through an SHA-256-verified Maven Wrapper.
- CI and release jobs use pinned Temurin `21.0.12.1+1`.
- Every GitHub Action is pinned to a complete commit SHA.
- The JAR and both CycloneDX SBOMs must reproduce byte-for-byte in a second
  clean build before release publication.
- GitHub build-provenance attestations cover the JAR, both SBOMs, and
  `SHA256SUMS`.
- Dependabot updates are grouped and limited to reduce pull-request noise.

## Dependency Maintenance

- Maven Resources Plugin `3.5.0`;
- Maven Compiler Plugin `3.15.0`;
- Maven Surefire Plugin `3.5.6`;
- Exec Maven Plugin `3.6.3`;
- Jackson Databind/Core remain on the security-cleared `2.22.2` line.

No Android, app, core, routing, server, feed, database, signing key, or
production configuration is included or modified.
