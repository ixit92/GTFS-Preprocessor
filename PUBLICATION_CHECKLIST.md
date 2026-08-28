# Publication Checklist

The extracted tree is technically buildable but must not be made public until
the repository owner completes these decisions and checks:

- [ ] Select and add an open-source or source-available `LICENSE`.
- [ ] Confirm ownership of the IXIT name and all original source code.
- [ ] Confirm redistribution rights for the bundled RNV route-color catalog.
- [ ] Preserve the VBB route-color attribution and CC BY 4.0 notice.
- [ ] Verify dependency licenses for the exact versions in `pom.xml`.
- [ ] Run `mvn clean package` from a fresh clone on Linux and Windows.
- [ ] Run a secret scan over the exported working tree and its new history.
- [ ] Confirm that no GTFS ZIP, SQLite file, report, key, or server path is
      present.
- [ ] Create the GitHub repository without importing the private repository.
- [ ] Enable secret scanning, push protection, Dependabot, and CodeQL.
- [ ] Add a private vulnerability-reporting contact to `SECURITY.md`.
- [ ] Publish a `v0.9.7` source/JAR release with a SHA-256 checksum.

Suggested license choices:

- Apache-2.0 for broad use with an explicit patent grant.
- AGPL-3.0 if modified network-service deployments should publish source.
- No license for a source-visible portfolio where reuse is not permitted.

