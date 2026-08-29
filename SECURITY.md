# Security Policy

## Supported Version

Security fixes target the latest published source and contract version.

## Reporting

Do not open a public issue for a vulnerability that exposes credentials,
private input data, filesystem escape, unsafe archive handling, SQL injection,
or artifact-signing weaknesses. Submit a private report through
[GitHub Security Advisories](https://github.com/ixit92/GTFS-Preprocessor/security/advisories/new).
Include affected versions, reproduction steps, impact, and any suggested
mitigation. Please do not include real GTFS feeds, credentials, or private keys.

## Trust Boundaries

GTFS ZIP files and GeoJSON municipality inputs are untrusted data. Output paths
must remain confined to the tool directory. Signing keys, when used by optional
mobile packaging commands, must remain outside Git and must never be included
in reports or generated packages.
