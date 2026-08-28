package de.ixit.gtfs;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RealFeedDriftAuditCli {
    private RealFeedDriftAuditCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            RealFeedDriftReport report = RealFeedDriftAuditor.audit(
                    options.baselineContractReport(),
                    options.candidateContractReport(),
                    options.candidateAuditReport(),
                    options.baselineSources(),
                    options.candidateSources()
            );
            RealFeedDriftAuditor.write(options.output(), report);
            System.out.println("Real-feed drift audit: " + (report.pass() ? "PASS" : "FAIL"));
            System.out.println("Baseline promotion: " + report.baselinePromotionState());
            System.out.println("Candidate audit compatibility: " + report.candidateAuditCompatibility());
            System.out.println("Maximum reported used heap: " + report.maximumReportedUsedHeapMb()
                    + " MB (limit " + report.maxHeapLimitMb() + " MB)");
            System.out.println("Report: " + options.output().toAbsolutePath().normalize());
            if (!report.pass()) {
                report.failures().forEach(failure -> System.err.println("- " + failure));
                System.exit(1);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Real-feed drift audit failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private record Options(
            Path baselineContractReport,
            Path candidateContractReport,
            Path candidateAuditReport,
            Path output,
            Map<String, String> baselineSources,
            Map<String, String> candidateSources
    ) {
        private static Options parse(String[] args) {
            Path baseline = null;
            Path candidate = null;
            Path audit = null;
            Path output = null;
            Map<String, String> baselineSources = new LinkedHashMap<>();
            Map<String, String> candidateSources = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--baseline-contract-report" -> baseline = Path.of(requireValue(args, ++index, "--baseline-contract-report"));
                    case "--candidate-contract-report" -> candidate = Path.of(requireValue(args, ++index, "--candidate-contract-report"));
                    case "--candidate-audit-report" -> audit = Path.of(requireValue(args, ++index, "--candidate-audit-report"));
                    case "--output" -> output = Path.of(requireValue(args, ++index, "--output"));
                    case "--baseline-source" -> addSource(baselineSources, requireValue(args, ++index, "--baseline-source"));
                    case "--candidate-source" -> addSource(candidateSources, requireValue(args, ++index, "--candidate-source"));
                    case "--help", "-h" -> throw new IllegalArgumentException("IXIT v0.7.4 real-feed drift audit");
                    default -> throw new IllegalArgumentException("Unknown drift-audit option: " + args[index]);
                }
            }
            if (baseline == null || candidate == null || audit == null || output == null) {
                throw new IllegalArgumentException("Baseline, candidate, audit and output are required");
            }
            if (baselineSources.isEmpty() || candidateSources.isEmpty()) {
                throw new IllegalArgumentException("Baseline and candidate source hashes are required");
            }
            return new Options(baseline, candidate, audit, output, Map.copyOf(baselineSources), Map.copyOf(candidateSources));
        }

        private static void addSource(Map<String, String> hashes, String value) {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException("Source must use NAME=SHA256");
            }
            String name = value.substring(0, separator);
            String hash = value.substring(separator + 1).toLowerCase(java.util.Locale.ROOT);
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid SHA-256 for source " + name);
            }
            hashes.put(name, hash);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("-")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static String usage() {
            return "Usage: feed-drift-audit --baseline-contract-report PATH --candidate-contract-report PATH "
                    + "--candidate-audit-report PATH --output PATH --baseline-source NAME=SHA256 "
                    + "--candidate-source NAME=SHA256";
        }
    }
}
