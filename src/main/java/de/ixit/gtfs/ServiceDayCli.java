package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ServiceDayCli {
    private ServiceDayCli() {
    }

    public static void main(String[] args) {
        try {
            Arguments arguments = Arguments.parse(args);
            Path database = arguments.database().toAbsolutePath().normalize();
            if (!Files.isRegularFile(database)) {
                throw new IllegalArgumentException("SQLite database does not exist: " + database);
            }

            ServiceDayResolver.ServiceDayResolution resolution =
                    ServiceDayResolver.resolve(database, arguments.serviceId(), arguments.date());
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("model_version", ServiceDayModelAuditor.MODEL_VERSION);
            output.put("database", database.toString());
            output.put("service_id", resolution.serviceId());
            output.put("service_date", resolution.serviceDate().toString());
            output.put("active", resolution.active());
            output.put("reason", resolution.reason());
            output.put("base_calendar_present", resolution.baseCalendarPresent());
            output.put("exception_type", resolution.exceptionType());
            output.put("service_timezone", resolution.serviceTimezone());
            output.put("raw_trip_count", resolution.rawTripCount());
            output.put("active_trip_count", resolution.activeTripCount());
            output.put("trip_samples", resolution.active() ? tripSamples(database, resolution.serviceId()) : List.of());
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output));
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            printUsage();
            System.exit(2);
        } catch (Exception ex) {
            System.err.println("Service-day check failed: " + ex.getMessage());
            ex.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static List<String> tripSamples(Path database, String serviceId) throws Exception {
        String jdbcUrl = "jdbc:sqlite:" + database.toUri() + "?mode=ro&immutable=1";
        List<String> tripIds = new ArrayList<>();
        try (var connection = DriverManager.getConnection(jdbcUrl);
             var statement = connection.prepareStatement(
                     "SELECT trip_id FROM trips WHERE service_id = ? ORDER BY trip_id LIMIT 20")) {
            statement.setString(1, serviceId);
            try (var resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    tripIds.add(resultSet.getString(1));
                }
            }
        }
        return List.copyOf(tripIds);
    }

    private record Arguments(Path database, String serviceId, LocalDate date) {
        private static Arguments parse(String[] args) {
            Path database = null;
            String serviceId = null;
            LocalDate date = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--database" -> database = Path.of(requiredValue(args, ++index, "--database"));
                    case "--service-id" -> serviceId = requiredValue(args, ++index, "--service-id");
                    case "--date" -> date = LocalDate.parse(requiredValue(args, ++index, "--date"));
                    case "--help", "-h" -> {
                        printUsage();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[index]);
                }
            }
            if (database == null || serviceId == null || date == null) {
                throw new IllegalArgumentException("--database, --service-id and --date are required");
            }
            return new Arguments(database, serviceId, date);
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
                  java -jar target/gtfs-preprocessor-0.9.8.jar service-day --database build/ixit_gtfs.sqlite --service-id SERVICE_ID --date YYYY-MM-DD
                """);
    }
}
