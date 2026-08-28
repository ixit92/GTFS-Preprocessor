package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class DisplayNameQualityBaselineCli {
    private DisplayNameQualityBaselineCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Path database = arguments.database().toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)) {
            throw new IllegalArgumentException("SQLite database does not exist: " + database);
        }

        DisplayNameQualityBaselineBuilder.BuildResult result;
        String jdbcUrl = "jdbc:sqlite:" + database.toUri() + "?mode=ro&immutable=1";
        try (var connection = DriverManager.getConnection(jdbcUrl)) {
            result = DisplayNameQualityBaselineBuilder.build(connection);
        }

        Map<String, TreeSet<String>> prefixesByClassification = new TreeMap<>();
        result.findings().stream()
                .filter(finding -> "UPPERCASE_PREFIX".equals(finding.findingType()))
                .forEach(finding -> prefixesByClassification
                        .computeIfAbsent(finding.classification(), ignored -> new TreeSet<>())
                        .add(finding.prefix()));

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("database", database.toString());
        output.put("baseline_version", result.report().baselineVersion());
        output.put("pass", result.report().pass());
        output.put("finding_count", result.report().findingCount());
        output.put("prefix_finding_count", result.report().prefixFindingCount());
        output.put("municipality_only_finding_count", result.report().municipalityOnlyFindingCount());
        output.put("destructive_action_count", result.report().destructiveActionCount());
        output.put("classification_counts", result.report().classificationCounts());
        output.put("prefixes_by_classification", prefixesByClassification);
        output.put("findings", result.findings());

        String json = new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output);
        if (arguments.output() == null) {
            System.out.println(json);
        } else {
            Path outputPath = arguments.output().toAbsolutePath().normalize();
            Path parent = outputPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(outputPath, json + System.lineSeparator());
            System.out.println("Display quality baseline written to " + outputPath);
        }
    }

    private record Arguments(Path database, Path output) {
        private static Arguments parse(String[] args) {
            Path database = null;
            Path output = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--database" -> database = Path.of(requiredValue(args, ++index, "--database"));
                    case "--output" -> output = Path.of(requiredValue(args, ++index, "--output"));
                    case "--help", "-h" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[index]);
                }
            }
            if (database == null) {
                throw new IllegalArgumentException("Missing required --database path");
            }
            return new Arguments(database, output);
        }

        private static String requiredValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].isBlank()) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static void printUsage() {
            System.out.println("""
                    Usage:
                      java -cp target/gtfs-preprocessor-0.9.7-SNAPSHOT.jar de.ixit.gtfs.DisplayNameQualityBaselineCli --database path/to/runtime.sqlite [--output report.json]
                    """);
        }
    }
}
