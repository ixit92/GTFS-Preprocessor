package de.ixit.gtfs;

import java.nio.file.Path;
import java.nio.file.Files;
import java.net.URISyntaxException;
import java.util.Arrays;

public final class GtfsPreprocessorCli {
    private GtfsPreprocessorCli() {
    }

    public static void main(String[] args) {
        if (args.length == 1 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("IXIT GTFS Preprocessor");
            System.out.println();
            System.out.println(CliOptions.usage());
            return;
        }
        if (args.length > 0 && "fuse".equalsIgnoreCase(args[0])) {
            GtfsFeedFusionCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "service-day".equalsIgnoreCase(args[0])) {
            ServiceDayCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "service-day-audit".equalsIgnoreCase(args[0])) {
            ServiceDayRealFeedAuditCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "cleanup-audit".equalsIgnoreCase(args[0])) {
            AuditArtifactCleanupCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "soak-compare".equalsIgnoreCase(args[0])) {
            SoakCycleComparisonCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "feed-drift-audit".equalsIgnoreCase(args[0])) {
            RealFeedDriftAuditCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "routing-contract-poc".equalsIgnoreCase(args[0])) {
            RoutingContractConsumerPocCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "routing-contract-real-feed-audit".equalsIgnoreCase(args[0])) {
            RoutingContractRealFeedAuditCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "mobile-package".equalsIgnoreCase(args[0])) {
            MobileArtifactPackagerCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (args.length > 0 && "mobile-update-contract".equalsIgnoreCase(args[0])) {
            MobileArtifactUpdateContractCli.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        try {
            CliOptions options = CliOptions.parse(args);
            PreprocessReport report = new GtfsPreprocessor().run(options.inputZip(), options.outputDatabase(), options.reportOutput(), options.preprocessOptions());
            System.out.println(report.toConsoleText());
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println();
            System.err.println(CliOptions.usage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("GTFS preprocessing failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    record CliOptions(Path inputZip, Path outputDatabase, Path reportOutput, PreprocessOptions preprocessOptions) {
        static CliOptions parse(String[] args) {
            Path toolRoot = detectToolRoot();
            Path input = null;
            Path output = toolRoot.resolve(Path.of("build", "ixit_gtfs.sqlite")).normalize();
            Path reportOutput = null;
            Path municipalityGeoJson = null;
            String municipalityDataVersion = "";
            PreprocessOptions preprocessOptions = PreprocessOptions.defaults();

            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if ("--input".equals(arg) || "-i".equals(arg)) {
                    input = requireValue(args, ++index, arg);
                } else if ("--output".equals(arg) || "-o".equals(arg)) {
                    output = resolveOutput(toolRoot, requireValue(args, ++index, arg));
                } else if ("--report-output".equals(arg)) {
                    reportOutput = resolveOutput(toolRoot, requireValue(args, ++index, arg));
                } else if ("--municipalities-geojson".equals(arg)) {
                    municipalityGeoJson = requireValue(args, ++index, arg).toAbsolutePath().normalize();
                } else if ("--municipalities-version".equals(arg)) {
                    municipalityDataVersion = requireValue(args, ++index, arg).toString();
                } else if ("--stress-core-only".equals(arg) || "--skip-derived-builders".equals(arg)) {
                    preprocessOptions = PreprocessOptions.stressCoreOnly();
                } else if ("--app-runtime".equals(arg)) {
                    preprocessOptions = PreprocessOptions.appRuntime();
                } else if ("--run-mode".equals(arg)) {
                    preprocessOptions = parseRunMode(requireValue(args, ++index, arg).toString());
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    throw new IllegalArgumentException("IXIT GTFS Preprocessor");
                } else if (arg.startsWith("-")) {
                    throw new IllegalArgumentException("Unknown option: " + arg);
                } else if (input == null) {
                    input = Path.of(arg);
                } else {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
            }

            if (input == null) {
                throw new IllegalArgumentException("Missing GTFS ZIP input.");
            }
            if (municipalityGeoJson != null || !municipalityDataVersion.isBlank()) {
                preprocessOptions = preprocessOptions.withMunicipalityData(
                        municipalityGeoJson,
                        municipalityDataVersion
                );
            }
            return new CliOptions(input, output, reportOutput, preprocessOptions);
        }

        private static Path requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return Path.of(args[index]);
        }

        static String usage() {
            return """
                    Usage:
                      ./mvnw exec:java -Dexec.args="--input path/to/gtfs.zip --output build/ixit_gtfs.sqlite"
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar --input path/to/gtfs.zip --output build/ixit_gtfs.sqlite --report-output build/ixit_gtfs_contract_report.json --app-runtime
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar fuse --source DE_FULL=de.zip --source CH=ch.zip --output build/de-ch-fused.zip
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar service-day --database build/ixit_gtfs.sqlite --service-id SERVICE_ID --date YYYY-MM-DD
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar service-day-audit --database build/v0.7.sqlite --baseline-database build/v0.6.4.sqlite --preprocess-report build/v0.7-report.json --output build/v0.8-audit.json --input-provenance copied-cache --source DE_FULL=SHA256 --spotcheck-date YYYY-MM-DD
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar cleanup-audit --tool-root . --audit-root build/v0.7.4 [--execute] [--delete-input-copies]
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar soak-compare --baseline-contract-report baseline.json --candidate-contract-report candidate.json --candidate-audit-report audit.json --output comparison.json --cycle-id cycle-01 --max-used-heap-mb 2300
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar feed-drift-audit --baseline-contract-report baseline.json --candidate-contract-report candidate.json --candidate-audit-report audit.json --output drift.json --baseline-source DE_FULL=SHA256 --candidate-source DE_FULL=SHA256
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar routing-contract-poc --database build/ixit_gtfs.sqlite --date YYYY-MM-DD --start-area AREA_ID --target-area AREA_ID --from 24:00:00 --to 27:00:00
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar routing-contract-real-feed-audit --tool-root . --database build/ixit_gtfs.sqlite --scenarios build/v0.8.1/scenarios.json --output build/v0.8.1/audit.json --input-provenance copied-cache --source DE_FULL=SHA256 --required-city Dortmund
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar mobile-package --tool-root . --database build/ixit_gtfs.sqlite --output-dir build/mobile/dortmund --seed-area AREA_ID --artifact-id de-dortmund-v2 --private-key local-data/mobile-signing-private.pem --public-key local-data/mobile-signing-public.pem --profile searchable-v2
                      java -jar target/gtfs-preprocessor-0.9.9-SNAPSHOT.jar mobile-update-contract --tool-root . --package-dir build/mobile/dortmund --output-dir build/mobile/dortmund-update --download-base-url https://updates.example.invalid/dortmund/ --sequence 1 --expires-at 2026-09-01T00:00:00Z --private-key local-data/mobile-signing-private.pem --public-key local-data/mobile-signing-public.pem

                    Options:
                      -i, --input              Required GTFS ZIP file
                      -o, --output             SQLite output file inside this tool, defaults to build/ixit_gtfs.sqlite
                      --report-output          JSON report file inside this tool, defaults next to SQLite output
                      --municipalities-geojson Versioned municipality FeatureCollection in EPSG:4326
                      --municipalities-version Required immutable version label for the municipality data
                      --app-runtime            App runtime run: build all derived tables and fail if app_ready_sqlite=false
                      --run-mode MODE          One of full, core-only, app-runtime
                      --stress-core-only       Diagnostic run: skip HubProfiles, RouteAxis and TransferRules builders
                      --skip-derived-builders  Alias for --stress-core-only
                    """;
        }

        private static PreprocessOptions parseRunMode(String value) {
            String normalized = value.trim().toLowerCase().replace('_', '-');
            return switch (normalized) {
                case "full" -> PreprocessOptions.defaults();
                case "core-only", "coreonly", "stress-core-only" -> PreprocessOptions.stressCoreOnly();
                case "app-runtime", "appruntime" -> PreprocessOptions.appRuntime();
                default -> throw new IllegalArgumentException("Unknown run mode: " + value);
            };
        }

        private static Path resolveOutput(Path toolRoot, Path requestedOutput) {
            Path resolved = requestedOutput.isAbsolute()
                    ? requestedOutput.normalize()
                    : toolRoot.resolve(requestedOutput).normalize();
            if (!resolved.startsWith(toolRoot)) {
                throw new IllegalArgumentException("Output must stay inside tools/gtfs-preprocessor: " + requestedOutput);
            }
            return resolved;
        }

        private static Path detectToolRoot() {
            try {
                Path codeLocation = Path.of(GtfsPreprocessorCli.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(codeLocation)) {
                    return codeLocation.getParent().getParent().toAbsolutePath().normalize();
                }
                if (codeLocation.endsWith(Path.of("target", "classes"))) {
                    return codeLocation.getParent().getParent().toAbsolutePath().normalize();
                }
            } catch (URISyntaxException | NullPointerException ex) {
                // Fall back to the process directory when the code source is unavailable.
            }
            return Path.of("").toAbsolutePath().normalize();
        }
    }
}
