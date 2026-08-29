package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DisplayNameAuditCli {
    private DisplayNameAuditCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        Path database = arguments.database().toAbsolutePath().normalize();
        if (!Files.isRegularFile(database)) {
            throw new IllegalArgumentException("SQLite database does not exist: " + database);
        }

        String jdbcUrl = "jdbc:sqlite:" + database.toUri() + "?mode=ro&immutable=1";
        DisplayNameAuditReport audit;
        try (var connection = DriverManager.getConnection(jdbcUrl)) {
            audit = DisplayNameAuditor.audit(connection);
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("database", database.toString());
        output.put("audit_version", audit.auditVersion());
        output.put("available", audit.available());
        output.put("pass", audit.pass());
        output.put("scanned_names", audit.scannedNames());
        output.put("format_mismatches", audit.formatMismatches());
        output.put("municipality_only_names", audit.municipalityOnlyNames());
        output.put("duplicate_city_name_prefixes", audit.duplicateCityNamePrefixes());
        output.put("matching_city_code_prefixes", audit.matchingCityCodePrefixes());
        output.put("matching_city_qualifiers", audit.matchingCityQualifiers());
        output.put("suspicious_unknown_prefixes", audit.suspiciousUnknownPrefixes());
        output.put("samples", audit.samples());
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output));

        if (arguments.requirePass() && !audit.pass()) {
            System.exit(2);
        }
    }

    private record Arguments(Path database, boolean requirePass) {
        private static Arguments parse(String[] args) {
            Path database = null;
            boolean requirePass = false;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--database" -> database = Path.of(requiredValue(args, ++index, "--database"));
                    case "--require-pass" -> requirePass = true;
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
            return new Arguments(database, requirePass);
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
                      java -cp target/gtfs-preprocessor-0.9.7.jar de.ixit.gtfs.DisplayNameAuditCli --database path/to/runtime.sqlite [--require-pass]
                    """);
        }
    }
}
