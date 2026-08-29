# Publication Checklist

The extracted tree is ready for its initial source push. Complete the remaining
operational checks before publishing the first binary release:

- [x] License Java/build files under Apache-2.0 and documentation/data under
      CC BY 4.0.
- [x] Repository owner approved publication of the IXIT source export.
- [x] Record the owner's confirmation that the bundled RNV route-color catalog
      is available under CC BY 4.0.
- [x] Preserve VBB and RNV attribution and CC BY 4.0 notices.
- [x] Verify and document runtime dependency licenses for the exact versions in
      `pom.xml`.
- [x] Run `mvn clean package` from fresh Windows and Linux clones. GitHub
      Actions run `33164983915` completed successfully.
- [x] Scan the exported working tree and its single new commit for credential
      and private-infrastructure patterns.
- [x] Confirm that no GTFS ZIP, SQLite file, report, key, or server path is
      present.
- [x] Create the GitHub repository without importing the private repository.
- [x] Enable secret scanning, push protection, Dependabot, and CodeQL.
- [x] Add a private vulnerability-reporting contact to `SECURITY.md`.
- [x] Publish a `v0.9.7` source/JAR release with a SHA-256 checksum.

The project license split is documented in `README.md`, `LICENSE`,
`LICENSES/CC-BY-4.0.md`, and `THIRD_PARTY_NOTICES.md`.
