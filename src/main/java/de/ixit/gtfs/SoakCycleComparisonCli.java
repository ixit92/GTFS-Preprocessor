package de.ixit.gtfs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SoakCycleComparisonCli {
    private SoakCycleComparisonCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            SoakCycleComparisonReport report = SoakCycleComparator.compare(
                    options.baselineContractReport(),
                    options.candidateContractReport(),
                    options.candidateAuditReport(),
                    options.candidatePreprocessLog(),
                    options.cycleId(),
                    options.maxHeapMb(),
                    options.sourceFeedHashes()
            );
            SoakCycleComparator.write(options.output(), report);
            System.out.println("Soak cycle comparison: " + (report.pass() ? "PASS" : "FAIL"));
            System.out.println("Report: " + options.output().toAbsolutePath().normalize());
            System.out.println("Maximum reported used heap: " + report.maximumReportedUsedHeapMb() + " MB");
            if (!report.pass()) {
                report.failures().forEach(failure -> System.err.println("- " + failure));
                System.exit(1);
            }
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("Soak cycle comparison failed: " + ex.getMessage());
            System.exit(1);
        }
    }

    private record Options(
            Path baselineContractReport,
            Path candidateContractReport,
            Path candidateAuditReport,
            Path candidatePreprocessLog,
            Path output,
            String cycleId,
            int maxHeapMb,
            Map<String, String> sourceFeedHashes
    ) {
        private static Options parse(String[] args) {
            Path baseline = null;
            Path candidate = null;
            Path audit = null;
            Path preprocessLog = null;
            Path output = null;
            String cycleId = null;
            int maxHeapMb = 2300;
            Map<String, String> sourceHashes = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--baseline-contract-report" -> baseline = requirePath(args, ++index, "--baseline-contract-report");
                    case "--candidate-contract-report" -> candidate = requirePath(args, ++index, "--candidate-contract-report");
                    case "--candidate-audit-report" -> audit = requirePath(args, ++index, "--candidate-audit-report");
                    case "--candidate-preprocess-log" -> preprocessLog = requirePath(args, ++index, "--candidate-preprocess-log");
                    case "--output" -> output = requirePath(args, ++index, "--output");
                    case "--cycle-id" -> cycleId = requireValue(args, ++index, "--cycle-id");
                    case "--max-used-heap-mb", "--max-heap-mb" -> maxHeapMb = Integer.parseInt(requireValue(args, ++index, args[index - 1]));
                    case "--source" -> addSource(sourceHashes, requireValue(args, ++index, "--source"));
                    case "--help", "-h" -> throw new IllegalArgumentException("IXIT v0.7.4 soak comparison");
                    default -> throw new IllegalArgumentException("Unknown soak comparison option: " + args[index]);
                }
            }
            if (baseline == null || candidate == null || audit == null || output == null || cycleId == null) {
                throw new IllegalArgumentException("Baseline, candidate, audit, output and cycle ID are required");
            }
            if (maxHeapMb <= 0) {
                throw new IllegalArgumentException("--max-used-heap-mb must be positive");
            }
            return new Options(baseline, candidate, audit, preprocessLog, output, cycleId, maxHeapMb, Map.copyOf(sourceHashes));
        }

        private static void addSource(Map<String, String> hashes, String value) {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("--source must use NAME=SHA256");
            }
            String name = value.substring(0, separator);
            String hash = value.substring(separator + 1).toLowerCase();
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid SHA-256 for source " + name);
            }
            hashes.put(name, hash);
        }

        private static Path requirePath(String[] args, int index, String option) {
            return Path.of(requireValue(args, index, option));
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: soak-compare --baseline-contract-report PATH --candidate-contract-report PATH "
                    + "--candidate-audit-report PATH --output PATH --cycle-id ID "
                    + "[--candidate-preprocess-log PATH] [--max-used-heap-mb 2300] [--source NAME=SHA256]";
        }
    }
}
