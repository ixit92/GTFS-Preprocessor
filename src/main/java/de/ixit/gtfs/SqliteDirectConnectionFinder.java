package de.ixit.gtfs;

import de.ixit.gtfs.TransitDataAccess.ResolvedStopAreaData;
import de.ixit.gtfs.TransitDataAccess.DirectTransitLegData;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SqliteDirectConnectionFinder {
    private static final int DEFAULT_LIMIT = 10;
    private static final int DEFAULT_DEPARTURE_SCAN_LIMIT = 10_000;

    private final TransitDataAccess dataAccess;

    public SqliteDirectConnectionFinder(TransitDataAccess dataAccess) {
        if (dataAccess == null) {
            throw new IllegalArgumentException("dataAccess must not be null");
        }
        this.dataAccess = dataAccess;
    }

    public DirectSearchResult findDirectConnections(
            LocalDate date,
            String startAreaId,
            String targetAreaId,
            int fromSeconds,
            int toSeconds,
            int limit,
            int departureScanLimit
    ) throws SQLException {
        long startedNanos = System.nanoTime();
        if (date == null) {
            throw new IllegalArgumentException("date must not be null");
        }
        if (isBlank(startAreaId) || isBlank(targetAreaId)) {
            throw new IllegalArgumentException("startAreaId and targetAreaId must not be blank");
        }
        if (fromSeconds < 0 || toSeconds < fromSeconds) {
            throw new IllegalArgumentException("invalid departure window");
        }
        if (limit < 1 || departureScanLimit < 1) {
            throw new IllegalArgumentException("limit and departureScanLimit must be positive");
        }

        ResolvedStopAreaData startArea = dataAccess.resolveStopArea(startAreaId);
        ResolvedStopAreaData targetArea = dataAccess.resolveStopArea(targetAreaId);
        if (startArea == null) {
            throw new IllegalArgumentException("Start StopArea not found: " + startAreaId);
        }
        if (targetArea == null) {
            throw new IllegalArgumentException("Target StopArea not found: " + targetAreaId);
        }

        Set<String> activeServiceIds = dataAccess.findActiveServiceIds(date);
        List<DirectTransitLegData> directLegs = dataAccess.findDirectLegs(
                startAreaId,
                targetAreaId,
                date,
                fromSeconds,
                toSeconds,
                departureScanLimit
        );
        List<DirectConnectionData> results = directLegs.stream()
                .map(SqliteDirectConnectionFinder::toDirectConnectionData)
                .limit(limit)
                .toList();
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        return new DirectSearchResult(
                startArea,
                targetArea,
                activeServiceIds.size(),
                directLegs.size(),
                directLegs.size() >= departureScanLimit,
                directLegs.size(),
                results,
                elapsedMs
        );
    }

    private static DirectConnectionData toDirectConnectionData(DirectTransitLegData leg) {
        return new DirectConnectionData(
                leg.tripId(),
                leg.routeId(),
                leg.routeShortName(),
                leg.routeLongName(),
                leg.serviceId(),
                leg.startStopId(),
                leg.startStopName(),
                leg.targetStopId(),
                leg.targetStopName(),
                leg.startDepartureSeconds(),
                leg.targetArrivalSeconds(),
                leg.startSequence(),
                leg.targetSequence(),
                leg.durationMinutes(),
                leg.serviceActiveReason()
        );
    }

    public static void main(String[] args) throws Exception {
        CliOptions options = CliOptions.parse(args);
        try (SqliteTransitDataAccess dataAccess = new SqliteTransitDataAccess(options.database())) {
            SqliteDirectConnectionFinder finder = new SqliteDirectConnectionFinder(dataAccess);
            DirectSearchResult result = finder.findDirectConnections(
                    options.date(),
                    options.startAreaId(),
                    options.targetAreaId(),
                    options.fromSeconds(),
                    options.toSeconds(),
                    options.limit(),
                    options.departureScanLimit()
            );
            printResult(options, result);
        }
    }

    private static void printResult(CliOptions options, DirectSearchResult result) {
        System.out.println("IXIT SQLite Direct Connection Finder Scaffold");
        System.out.println("database=" + options.database().toAbsolutePath());
        System.out.println("date=" + options.date());
        System.out.println("window=" + formatSeconds(options.fromSeconds()) + "-" + formatSeconds(options.toSeconds()));
        System.out.println("start_area=" + result.startArea().areaId() + " " + result.startArea().displayName()
                + " members=" + result.startArea().members().size());
        System.out.println("target_area=" + result.targetArea().areaId() + " " + result.targetArea().displayName()
                + " members=" + result.targetArea().members().size());
        System.out.println("active_services=" + result.activeServiceCount());
        System.out.println("direct_legs_scanned=" + result.directLegsScanned());
        System.out.println("direct_leg_scan_limit_hit=" + result.directLegScanLimitHit());
        System.out.println("direct_candidates=" + result.directCandidateCount());
        System.out.println("returned=" + result.connections().size());
        System.out.println("elapsed_ms=" + result.elapsedMs());
        for (DirectConnectionData connection : result.connections()) {
            System.out.println("- trip_id=" + connection.tripId()
                    + " route_id=" + connection.routeId()
                    + " route_short_name=" + connection.routeShortName()
                    + " service_id=" + connection.serviceId()
                    + " service_active_reason=" + connection.serviceActiveReason());
            System.out.println("  start_stop_id=" + connection.startStopId()
                    + " start_stop_name=" + connection.startStopName()
                    + " departure=" + formatSeconds(connection.startDepartureSeconds())
                    + " sequence=" + connection.startSequence());
            System.out.println("  target_stop_id=" + connection.targetStopId()
                    + " target_stop_name=" + connection.targetStopName()
                    + " arrival=" + formatSeconds(connection.targetArrivalSeconds())
                    + " sequence=" + connection.targetSequence()
                    + " duration_minutes=" + connection.durationMinutes());
        }
    }

    private static int parseTimeSeconds(String value) {
        String[] parts = value.split(":");
        if (parts.length != 2 && parts.length != 3) {
            throw new IllegalArgumentException("Time must be HH:mm or HH:mm:ss: " + value);
        }
        int hours = Integer.parseInt(parts[0]);
        int minutes = Integer.parseInt(parts[1]);
        int seconds = parts.length == 3 ? Integer.parseInt(parts[2]) : 0;
        if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
            throw new IllegalArgumentException("Invalid time: " + value);
        }
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static String formatSeconds(int secondsSinceServiceDayStart) {
        int hours = secondsSinceServiceDayStart / 3600;
        int minutes = (secondsSinceServiceDayStart % 3600) / 60;
        int seconds = secondsSinceServiceDayStart % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public record DirectSearchResult(
            ResolvedStopAreaData startArea,
            ResolvedStopAreaData targetArea,
            int activeServiceCount,
            int directLegsScanned,
            boolean directLegScanLimitHit,
            int directCandidateCount,
            List<DirectConnectionData> connections,
            long elapsedMs
    ) {
    }

    public record DirectConnectionData(
            String tripId,
            String routeId,
            String routeShortName,
            String routeLongName,
            String serviceId,
            String startStopId,
            String startStopName,
            String targetStopId,
            String targetStopName,
            int startDepartureSeconds,
            int targetArrivalSeconds,
            int startSequence,
            int targetSequence,
            int durationMinutes,
            String serviceActiveReason
    ) {
    }

    private record CliOptions(
            Path database,
            LocalDate date,
            String startAreaId,
            String targetAreaId,
            int fromSeconds,
            int toSeconds,
            int limit,
            int departureScanLimit
    ) {
        static CliOptions parse(String[] args) {
            Map<String, String> options = new HashMap<>();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                options.put(arg.substring(2), args[++index]);
            }

            String startAreaId = required(options, "start-area");
            String targetAreaId = required(options, "target-area");
            Path database = Path.of(options.getOrDefault(
                    "database",
                    "build/gtfs-de-full-core-v0_5.sqlite"
            ));
            LocalDate date = LocalDate.parse(options.getOrDefault("date", LocalDate.now().toString()));
            int fromSeconds = parseTimeSeconds(options.getOrDefault("from", LocalTime.now().withSecond(0).toString()));
            int toSeconds = parseTimeSeconds(options.getOrDefault("to", "27:00"));
            int limit = Integer.parseInt(options.getOrDefault("limit", String.valueOf(DEFAULT_LIMIT)));
            int departureScanLimit = Integer.parseInt(options.getOrDefault(
                    "departure-scan-limit",
                    String.valueOf(DEFAULT_DEPARTURE_SCAN_LIMIT)
            ));
            return new CliOptions(database, date, startAreaId, targetAreaId, fromSeconds, toSeconds,
                    limit, departureScanLimit);
        }

        private static String required(Map<String, String> options, String key) {
            String value = options.get(key);
            if (isBlank(value)) {
                throw new IllegalArgumentException("Missing required option --" + key);
            }
            return value;
        }
    }
}
