package de.ixit.gtfs;

import de.ixit.gtfs.RoutingContractConsumerPoc.RoutingContractViolationException;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

public final class RoutingContractConsumerPocCli {
    private RoutingContractConsumerPocCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            RoutingContractConsumerReport report = RoutingContractConsumerPoc.inspect(
                    options.database(),
                    options.serviceDate(),
                    options.startAreaId(),
                    options.targetAreaId(),
                    options.fromSeconds(),
                    options.toSeconds(),
                    options.limit()
            );
            RoutingContractConsumerPoc.write(options.output(), report);
            System.out.println("Routing Contract Consumer PoC: " + (report.pass() ? "PASS" : "FAIL"));
            System.out.println("contract_version=" + report.contract().contractVersion());
            System.out.println("start_area=" + report.startArea().areaId()
                    + " concrete_stops=" + report.startArea().concreteMembers().size());
            System.out.println("target_area=" + report.targetArea().areaId()
                    + " concrete_stops=" + report.targetArea().concreteMembers().size());
            System.out.println("validated_legs=" + report.validatedLegs().size());
            System.out.println("over_24h_observed=" + report.overflowTimeObserved());
            System.out.println("report=" + options.output().toAbsolutePath().normalize());
            if (!report.pass()) {
                report.failures().forEach(failure -> System.err.println("- " + failure));
                System.exit(1);
            }
        } catch (RoutingContractViolationException exception) {
            System.err.println(exception.getMessage());
            System.exit(1);
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Routing Contract Consumer PoC failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private record Options(
            Path database,
            Path output,
            LocalDate serviceDate,
            String startAreaId,
            String targetAreaId,
            int fromSeconds,
            int toSeconds,
            int limit
    ) {
        private static Options parse(String[] args) {
            Path toolRoot = detectToolRoot();
            Path database = null;
            Path output = toolRoot.resolve(Path.of("build", "routing-contract-consumer-v0.8.json"));
            LocalDate serviceDate = null;
            String startAreaId = null;
            String targetAreaId = null;
            int fromSeconds = 0;
            int toSeconds = GtfsTimeParser.toSecondsSinceServiceDayStart("27:00:00");
            int limit = 10;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--database" -> database = Path.of(requireValue(args, ++index, "--database"));
                    case "--output" -> output = resolveOutput(toolRoot, Path.of(requireValue(args, ++index, "--output")));
                    case "--date" -> serviceDate = LocalDate.parse(requireValue(args, ++index, "--date"));
                    case "--start-area" -> startAreaId = requireValue(args, ++index, "--start-area");
                    case "--target-area" -> targetAreaId = requireValue(args, ++index, "--target-area");
                    case "--from" -> fromSeconds = parseTime(requireValue(args, ++index, "--from"));
                    case "--to" -> toSeconds = parseTime(requireValue(args, ++index, "--to"));
                    case "--limit" -> limit = Integer.parseInt(requireValue(args, ++index, "--limit"));
                    case "--help", "-h" -> throw new IllegalArgumentException("IXIT v0.8 Routing Contract Consumer PoC");
                    default -> throw new IllegalArgumentException("Unknown routing-contract-poc option: " + args[index]);
                }
            }
            if (database == null || serviceDate == null || isBlank(startAreaId) || isBlank(targetAreaId)) {
                throw new IllegalArgumentException("--database, --date, --start-area and --target-area are required");
            }
            return new Options(database, output, serviceDate, startAreaId, targetAreaId,
                    fromSeconds, toSeconds, limit);
        }

        private static int parseTime(String value) {
            return GtfsTimeParser.toSecondsSinceServiceDayStart(
                    value.chars().filter(character -> character == ':').count() == 1 ? value + ":00" : value
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static Path resolveOutput(Path toolRoot, Path requested) {
            Path resolved = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : toolRoot.resolve(requested).normalize();
            if (!resolved.startsWith(toolRoot)) {
                throw new IllegalArgumentException("Output must stay inside tools/gtfs-preprocessor: " + requested);
            }
            return resolved;
        }

        private static Path detectToolRoot() {
            try {
                Path codeLocation = Path.of(RoutingContractConsumerPocCli.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(codeLocation)) {
                    return codeLocation.getParent().getParent().toAbsolutePath().normalize();
                }
                if (codeLocation.endsWith(Path.of("target", "classes"))) {
                    return codeLocation.getParent().getParent().toAbsolutePath().normalize();
                }
            } catch (URISyntaxException | NullPointerException exception) {
                // Fall through to the process directory.
            }
            return Path.of("").toAbsolutePath().normalize();
        }

        private static boolean isBlank(String value) {
            return value == null || value.trim().isEmpty();
        }

        private static String usage() {
            return "Usage: routing-contract-poc --database PATH --date YYYY-MM-DD "
                    + "--start-area AREA_ID --target-area AREA_ID [--from HH:mm:ss] "
                    + "[--to HH:mm:ss] [--limit N] [--output build/report.json]";
        }
    }
}
