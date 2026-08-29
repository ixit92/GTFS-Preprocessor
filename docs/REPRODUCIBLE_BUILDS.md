# Reproducible Builds

IXIT GTFS Preprocessor release builds use a deliberately narrow build
environment:

- Maven `3.9.16` is supplied by the checked-in Maven Wrapper;
- the Maven distribution is verified with SHA-256 before use;
- CI and release workflows use Temurin `21.0.12.1` (Temurin build 1);
- GitHub Actions are pinned to complete commit SHAs;
- text inputs use repository-defined line endings;
- JAR timestamps use `project.build.outputTimestamp`;
- CycloneDX SBOMs omit volatile serial-number and timestamp fields.

Build and test once:

```bash
./mvnw clean package
```

Then reproduce the package from the same checkout and toolchain:

```bash
./scripts/verify-reproducible-build.sh
```

On Windows, run the first build with `mvnw.cmd` and execute the verification
script through Git Bash.

The script records SHA-256 values for the shaded JAR and both SBOMs, performs a
second clean package build with tests skipped, and fails if any byte differs.
Tests are never skipped in the first build.

Reproducibility is guaranteed for the pinned release environment. A different
JDK vendor or patch level may emit different class files even when targeting
Java 21. Published `SHA256SUMS` values and GitHub artifact attestations identify
the canonical release-workflow output.
