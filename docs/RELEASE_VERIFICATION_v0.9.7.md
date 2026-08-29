# Release Verification: v0.9.7

Verification date: 2026-08-29

## Identity

- tag: `v0.9.7`
- source commit: `a09dc755fca127886cd819265e6d1a11b81a6f26`
- release: https://github.com/ixit92/GTFS-Preprocessor/releases/tag/v0.9.7
- release workflow: https://github.com/ixit92/GTFS-Preprocessor/actions/runs/33246726988
- source CI: https://github.com/ixit92/GTFS-Preprocessor/actions/runs/33246530409
- CodeQL: https://github.com/ixit92/GTFS-Preprocessor/actions/runs/33246530411

## Published Artifacts

The downloaded release assets were verified against the published
`SHA256SUMS` file:

| Artifact | SHA-256 |
| --- | --- |
| `gtfs-preprocessor-0.9.7.jar` | `adaf10c69fef37222352c03a2eb0e34fe152f0f335854c5aa4394283538194a3` |
| `gtfs-preprocessor-0.9.7-sbom.json` | `2b37212f954839ec4b359361fa40207c5ab26499094ca27ba85352af49514516` |
| `gtfs-preprocessor-0.9.7-sbom.xml` | `88e667e4511b05f7070764740633b0f9db2b0fcecd7288459d7dcdd32f18a4de` |

## Verified Gates

- a clean Java 21 build from a fresh clone completed successfully;
- the complete embedded suite ran through JUnit with zero failures;
- Linux/Java 21 CI and CodeQL completed successfully;
- open Dependabot alerts: `0`;
- open CodeQL code-scanning alerts: `0`;
- secret scanning and push protection are enabled;
- private vulnerability reporting is enabled;
- downloaded release checksums all match;
- the JAR `--help` command exits with status `0`;
- the JAR contains Apache, CC BY 4.0, notice, and third-party attribution files;
- the JAR manifest records preprocessor `0.9.7`, schema `0.1`, and contract `0.8`;
- both SBOMs use CycloneDX `1.6`, omit volatile serial/timestamp fields, and
  record Jackson Databind `2.22.2`.
