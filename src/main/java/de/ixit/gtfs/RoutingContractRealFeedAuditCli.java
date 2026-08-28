package de.ixit.gtfs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoutingContractRealFeedAuditCli {
    private RoutingContractRealFeedAuditCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            RoutingContractRealFeedAuditReport report = RoutingContractRealFeedAuditor.audit(
                    options.database(),
                    options.scenarios(),
                    options.inputProvenance(),
                    options.sourceFeeds(),
                    options.requiredCities(),
                    options.maximumQueryMs(),
                    options.exceptionSampleLimit()
            );
            RoutingContractRealFeedAuditor.write(options.output(), report);
            System.out.println("Routing Contract Real Feed Audit v0.8.1: " + (report.pass() ? "PASS" : "FAIL"));
            System.out.println("database_sha256=" + report.databaseSha256());
            System.out.println("scenarios=" + report.scenarios().size());
            System.out.println("latency_p95_ms=" + report.latency().p95Ms());
            System.out.println("latency_max_ms=" + report.latency().maximumMs());
            System.out.println("calendar_additions=" + report.serviceDayExceptions().additionRowCount());
            System.out.println("calendar_removals=" + report.serviceDayExceptions().removalRowCount());
            System.out.println("overflow_stop_times=" + report.overflowTimes().overflowRowCount());
            System.out.println("report=" + options.output());
            if (!report.pass()) {
                report.failures().forEach(failure -> System.err.println("- " + failure));
                System.exit(1);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Routing Contract Real Feed Audit failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private record Options(
            Path toolRoot,
            Path database,
            Path scenarios,
            Path output,
            String inputProvenance,
            Map<String, String> sourceFeeds,
            List<String> requiredCities,
            long maximumQueryMs,
            int exceptionSampleLimit
    ) {
        private static Options parse(String[] args) {
            Path toolRoot = null;
            Path database = null;
            Path scenarios = null;
            Path output = null;
            String inputProvenance = null;
            Map<String, String> sources = new LinkedHashMap<>();
            List<String> cities = new ArrayList<>();
            long maximumQueryMs = 15_000;
            int exceptionSampleLimit = 10;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--tool-root" -> toolRoot = Path.of(requireValue(args, ++index, "--tool-root"));
                    case "--database" -> database = Path.of(requireValue(args, ++index, "--database"));
                    case "--scenarios" -> scenarios = Path.of(requireValue(args, ++index, "--scenarios"));
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    case "--input-provenance" -> inputProvenance = requireValue(args, ++index, "--input-provenance");
                    case "--source" -> putSource(sources, requireValue(args, ++index, "--source"));
                    case "--required-city" -> cities.add(requireValue(args, ++index, "--required-city"));
                    case "--max-query-ms" -> maximumQueryMs = Long.parseLong(requireValue(args, ++index, "--max-query-ms"));
                    case "--exception-samples" -> exceptionSampleLimit = Integer.parseInt(requireValue(args, ++index, "--exception-samples"));
                    case "--help", "-h" -> throw new IllegalArgumentException("IXIT v0.8.1 Routing Contract Real Feed Audit");
                    default -> throw new IllegalArgumentException("Unknown routing-contract-real-feed-audit option: " + args[index]);
                }
            }
            if (toolRoot == null || database == null || scenarios == null || output == null) {
                throw new IllegalArgumentException("--tool-root, --database, --scenarios and --output are required");
            }
            Path normalizedRoot = toolRoot.toAbsolutePath().normalize();
            if (!Files.isDirectory(normalizedRoot)) {
                throw new IllegalArgumentException("Tool root does not exist: " + normalizedRoot);
            }
            Path normalizedDatabase = confined(normalizedRoot, database, "database");
            Path normalizedScenarios = confined(normalizedRoot, scenarios, "scenarios");
            Path normalizedOutput = confined(normalizedRoot, output, "output");
            return new Options(
                    normalizedRoot,
                    normalizedDatabase,
                    normalizedScenarios,
                    normalizedOutput,
                    inputProvenance,
                    Map.copyOf(sources),
                    List.copyOf(cities),
                    maximumQueryMs,
                    exceptionSampleLimit
            );
        }

        private static Path confined(Path toolRoot, Path requested, String label) {
            Path resolved = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : toolRoot.resolve(requested).normalize();
            if (!resolved.startsWith(toolRoot)) {
                throw new IllegalArgumentException(label + " must stay inside tool root: " + requested);
            }
            return resolved;
        }

        private static void putSource(Map<String, String> sources, String value) {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("--source must use NAME=SHA256");
            }
            String previous = sources.put(value.substring(0, separator), value.substring(separator + 1));
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate source: " + value.substring(0, separator));
            }
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: routing-contract-real-feed-audit --tool-root PATH --database PATH "
                    + "--scenarios PATH --output PATH --input-provenance TEXT --source NAME=SHA256 "
                    + "--required-city CITY [--required-city CITY ...] [--max-query-ms N] "
                    + "[--exception-samples N]";
        }
    }
}
