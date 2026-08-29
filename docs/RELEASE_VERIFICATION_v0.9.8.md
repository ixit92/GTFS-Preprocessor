# Release Verification: v0.9.8

Verification date: 2026-08-29

## Identity

- tag: `v0.9.8`
- source commit: `d420d61c10d73d2c6662be59a1bca65389e6906d`
- release: https://github.com/ixit92/GTFS-Preprocessor/releases/tag/v0.9.8
- release workflow: https://github.com/ixit92/GTFS-Preprocessor/actions/runs/33254690564
- source CI: https://github.com/ixit92/GTFS-Preprocessor/actions/runs/33254452106
- CodeQL: https://github.com/ixit92/GTFS-Preprocessor/actions/runs/33254452103

## Published Artifacts

The downloaded release assets were verified against the published
`SHA256SUMS` file:

| Artifact | SHA-256 |
| --- | --- |
| `gtfs-preprocessor-0.9.8.jar` | `f37fec94cde9f66ac45b5717504230984237fe111e3413a7f871779be90df0c5` |
| `gtfs-preprocessor-0.9.8-sbom.json` | `111e5c7cce557db8b53b44f1690c53fdfa1ac10e516d74a988ccaeda9bb32071` |
| `gtfs-preprocessor-0.9.8-sbom.xml` | `6713b49050a46188ca790475e90b61da7508be879852d5ce6c53187d50b0e696` |
| `SHA256SUMS` | `e4553272097de36437ff385d368fe7468f9a3e1c4dbe21be96f56a8e801de32e` |

## Provenance

GitHub CLI `2.98.0` was downloaded as a portable verification tool and its
published SHA-256 digest was checked before use. `gh attestation verify`
then verified all four release assets independently.

Each verified SLSA provenance statement identifies:

- signer commit: `d420d61c10d73d2c6662be59a1bca65389e6906d`;
- source ref: `refs/tags/v0.9.8`;
- workflow: `Release`;
- runner environment: `github-hosted`.

## Verified Gates

- a clean Java 21 build from a fresh clone completed successfully;
- the JAR and both CycloneDX SBOMs reproduced byte-for-byte in the fresh
  checkout;
- the release workflow repeated the byte-for-byte build verification with
  pinned Temurin `21.0.12.1` before publication;
- the complete embedded JUnit suite completed with zero failures;
- source CI and CodeQL completed successfully;
- `main` requires the strict `build` and `Analyze Java` checks;
- force pushes and branch deletion are disabled for `main`;
- open pull requests: `0`;
- open Dependabot alerts: `0`;
- open CodeQL code-scanning alerts: `0`;
- open secret-scanning alerts: `0`;
- downloaded release checksums all match;
- provenance verification succeeds for the JAR, both SBOMs, and
  `SHA256SUMS`.

The SQLite schema remains `0.1` and the producer/consumer contract remains
`0.8`. This release changes no Android, app, core, routing, server, feed, or
runtime integration.
