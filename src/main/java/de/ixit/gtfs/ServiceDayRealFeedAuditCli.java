package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceDayRealFeedAuditCli {
    private ServiceDayRealFeedAuditCli() {
    }

    public static void main(String[] args) {
        try {
            Arguments arguments = Arguments.parse(args);
            ServiceDayRealFeedAuditReport report = ServiceDayRealFeedAuditor.audit(
                    arguments.database(),
                    arguments.baselineDatabase(),
                    arguments.preprocessReport(),
                    arguments.inputProvenance(),
                    arguments.sourceFeeds(),
                    arguments.spotcheckDates()
            );
            Path output = arguments.output().toAbsolutePath().normalize();
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), report);
            System.out.println("Service-day real-feed audit: " + (report.pass() ? "PASS" : "FAIL"));
            System.out.println("Report: " + output);
            System.out.println("Unresolved trip services: " + report.serviceDayModel().unresolvedTripServiceCount());
            System.out.println("Invalid IANA timezone services: " + report.serviceDayModel().invalidIanaTimezoneServiceCount());
            System.out.println("Overflow stop_times: " + report.serviceDayModel().overflowStopTimeCount());
            System.out.println("SQLite size delta: " + report.baselineComparison().sqliteFileSizeDeltaBytes());
            if (!report.failures().isEmpty()) {
                report.failures().forEach(failure -> System.err.println("- " + failure));
                System.exit(1);
            }
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            printUsage();
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Service-day real-feed audit failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private record Arguments(
            Path database,
            Path baselineDatabase,
            Path preprocessReport,
            Path output,
            String inputProvenance,
            Map<String, String> sourceFeeds,
            List<LocalDate> spotcheckDates
    ) {
        private static Arguments parse(String[] args) {
            Path database = null;
            Path baseline = null;
            Path preprocessReport = null;
            Path output = null;
            String provenance = null;
            Map<String, String> sources = new LinkedHashMap<>();
            List<LocalDate> dates = new ArrayList<>();
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--database" -> database = Path.of(requiredValue(args, ++index, "--database"));
                    case "--baseline-database" -> baseline = Path.of(requiredValue(args, ++index, "--baseline-database"));
                    case "--preprocess-report" -> preprocessReport = Path.of(requiredValue(args, ++index, "--preprocess-report"));
                    case "--output" -> output = Path.of(requiredValue(args, ++index, "--output"));
                    case "--input-provenance" -> provenance = requiredValue(args, ++index, "--input-provenance");
                    case "--source" -> addSource(sources, requiredValue(args, ++index, "--source"));
                    case "--spotcheck-date" -> dates.add(LocalDate.parse(requiredValue(args, ++index, "--spotcheck-date")));
                    case "--help", "-h" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            if (database == null || baseline == null || preprocessReport == null || output == null
                    || provenance == null || sources.isEmpty() || dates.isEmpty()) {
                throw new IllegalArgumentException("All audit options are required; --source and --spotcheck-date may repeat");
            }
            return new Arguments(
                    database,
                    baseline,
                    preprocessReport,
                    output,
                    provenance,
                    Map.copyOf(sources),
                    List.copyOf(dates)
            );
        }

        private static void addSource(Map<String, String> sources, String value) {
            int separator = value.indexOf('=');
            if (separator <= 0 || separator + 1 >= value.length()) {
                throw new IllegalArgumentException("--source must use NAME=SHA256");
            }
            sources.put(value.substring(0, separator), value.substring(separator + 1));
        }

        private static String requiredValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }
    }

    private static void printUsage() {
        System.err.println("""
                Usage:
                  java -jar target/gtfs-preprocessor-0.9.8.jar service-day-audit \
                    --database build/v0.7.sqlite \
                    --baseline-database build/v0.6.4.sqlite \
                    --preprocess-report build/v0.7-contract-report.json \
                    --output build/v0.7.4-service-day-audit.json \
                    --input-provenance "unchanged copies from active routing feed cache" \
                    --source DE_FULL=SHA256 --source CH=SHA256 \
                    --spotcheck-date 2026-08-08 --spotcheck-date 2026-08-09
                """);
    }
}
