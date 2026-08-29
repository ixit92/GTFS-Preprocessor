package de.ixit.gtfs;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GtfsFeedFusionCli {
    private GtfsFeedFusionCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            GtfsFeedFusionReport report = new GtfsFeedFusion().run(
                    options.sources(),
                    options.outputZip(),
                    options.reportOutput()
            );
            System.out.println(report.toConsoleText());
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println();
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("GTFS fusion failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    record Options(List<GtfsFeedFusion.Source> sources, Path outputZip, Path reportOutput) {
        static Options parse(String[] args) {
            Path toolRoot = detectToolRoot();
            List<GtfsFeedFusion.Source> sources = new ArrayList<>();
            Path output = toolRoot.resolve("build/ixit_gtfs_fused.zip").normalize();
            Path report = null;
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                switch (arg) {
                    case "--source", "-s" -> {
                        String value = requireValue(args, ++index, arg);
                        int separator = value.indexOf('=');
                        if (separator <= 0 || separator == value.length() - 1) {
                            throw new IllegalArgumentException("Source must use ID=path syntax: " + value);
                        }
                        sources.add(new GtfsFeedFusion.Source(
                                value.substring(0, separator).trim().toUpperCase(Locale.ROOT),
                                Path.of(value.substring(separator + 1).trim()),
                                sources.size()
                        ));
                    }
                    case "--output", "-o" -> output = resolveOutput(
                            toolRoot,
                            Path.of(requireValue(args, ++index, arg))
                    );
                    case "--report-output" -> report = resolveOutput(
                            toolRoot,
                            Path.of(requireValue(args, ++index, arg))
                    );
                    case "--help", "-h" -> throw new IllegalArgumentException("IXIT GTFS Feed Fusion");
                    default -> throw new IllegalArgumentException("Unknown fusion option: " + arg);
                }
            }
            if (sources.size() < 2) {
                throw new IllegalArgumentException("Specify at least two --source ID=path values");
            }
            return new Options(List.copyOf(sources), output, report);
        }

        static String usage() {
            return """
                    Usage:
                      java -jar target/gtfs-preprocessor-0.9.8.jar fuse \
                        --source DE_FULL=path/to/de.zip \
                        --source CH=path/to/ch.zip \
                        --output build/de-ch-fused.zip \
                        --report-output build/de-ch-fusion-report.json

                    Options:
                      -s, --source ID=ZIP     Repeatable source in priority order; first source wins exact duplicates
                      -o, --output ZIP        Fused GTFS ZIP inside tools/gtfs-preprocessor
                      --report-output JSON    Fusion diagnostics report inside tools/gtfs-preprocessor
                    """;
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static Path resolveOutput(Path toolRoot, Path requestedOutput) {
            Path resolved = requestedOutput.isAbsolute()
                    ? requestedOutput.toAbsolutePath().normalize()
                    : toolRoot.resolve(requestedOutput).normalize();
            if (!resolved.startsWith(toolRoot)) {
                throw new IllegalArgumentException(
                        "Fusion output must stay inside tools/gtfs-preprocessor: " + requestedOutput
                );
            }
            return resolved;
        }

        private static Path detectToolRoot() {
            try {
                Path codeLocation = Path.of(GtfsFeedFusionCli.class.getProtectionDomain()
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
