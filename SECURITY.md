# Security Policy

## Supported Version

Security fixes target the latest published source and contract version.

## Reporting

Do not open a public issue for a vulnerability that exposes credentials,
private input data, filesystem escape, unsafe archive handling, SQL injection,
or artifact-signing weaknesses. Use the repository owner's private GitHub
security-reporting channel after the repository is created.

## Trust Boundaries

GTFS ZIP files and GeoJSON municipality inputs are untrusted data. Output paths
must remain confined to the tool directory. Signing keys, when used by optional
mobile packaging commands, must remain outside Git and must never be included
in reports or generated packages.

