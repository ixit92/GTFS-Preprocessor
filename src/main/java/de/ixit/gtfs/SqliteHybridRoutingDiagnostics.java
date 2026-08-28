package de.ixit.gtfs;

import de.ixit.gtfs.TransitDataAccess.DirectTransitLegData;
import de.ixit.gtfs.TransitDataAccess.NextTransitLegData;
import de.ixit.gtfs.TransitDataAccess.ResolvedStopAreaData;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class SqliteHybridRoutingDiagnostics {
    private static final int DEFAULT_MAX_NEXT_LEGS_PER_AREA = 40;
    private static final int DEFAULT_MAX_RESULTS = 20;
    private static final int DEFAULT_MAX_SEARCH_MILLIS = 2_500;
    private static final int DEFAULT_MIN_TRANSFER_MINUTES = 3;
    private static final int DEFAULT_MAX_TRANSFER_WAIT_MINUTES = 45;
    private static final int DEFAULT_SECOND_LEG_WINDOW_MINUTES = 120;
    private static final int DEFAULT_DIRECT_SUFFICIENT_COUNT = 1;

    private SqliteHybridRoutingDiagnostics() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception exception) {
            System.err.println("SqliteHybridRoutingDiagnostics failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        Options options = Options.parse(args);
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + options.maxSearchMillis() * 1_000_000L;

        try (SqliteTransitDataAccess dataAccess = new SqliteTransitDataAccess(options.database())) {
            ResolvedStopAreaData startArea = requireArea(dataAccess, options.startAreaId(), "start");
            ResolvedStopAreaData targetArea = requireArea(dataAccess, options.targetAreaId(), "target");
            Set<String> activeServiceIds = dataAccess.findActiveServiceIds(options.date());

            List<DirectTransitLegData> directLegs = dataAccess.findDirectLegs(
                    options.startAreaId(),
                    options.targetAreaId(),
                    options.date(),
                    options.fromSeconds(),
                    options.toSeconds(),
                    options.maxResults()
            );

            boolean directSufficient = directLegs.size() >= options.directSufficientCount();
            boolean timeoutHit = deadlineReached(deadlineNanos);
            List<RouteCandidate> results = new ArrayList<>();
            for (DirectTransitLegData directLeg : directLegs) {
                results.add(RouteCandidate.direct(directLeg));
            }

            int expandedNextLegs = 0;
            int transferCandidates = 0;
            int secondLegQueries = 0;
            int visitedSkipped = 0;

            if (!directSufficient && !timeoutHit) {
                List<NextTransitLegData> nextLegs = dataAccess.findNextLegs(
                        options.startAreaId(),
                        options.date(),
                        options.fromSeconds(),
                        options.toSeconds(),
                        0,
                        activeServiceIds,
                        options.maxNextLegsPerArea()
                );
                expandedNextLegs = nextLegs.size();
                Set<VisitedKey> visited = new HashSet<>();
                int minTransferSeconds = options.minTransferMinutes() * 60;
                int absoluteLatestSecondDepartureSeconds = options.toSeconds() + options.secondLegWindowMinutes() * 60;

                for (NextTransitLegData firstLeg : nextLegs) {
                    if (deadlineReached(deadlineNanos) || results.size() >= options.maxResults()) {
                        timeoutHit = deadlineReached(deadlineNanos);
                        break;
                    }
                    if (options.targetAreaId().equals(firstLeg.toAreaId())) {
                        continue;
                    }
                    VisitedKey visitedKey = new VisitedKey(firstLeg.toAreaId(), firstLeg.tripId(), 1);
                    if (!visited.add(visitedKey)) {
                        visitedSkipped++;
                        continue;
                    }
                    int secondEarliestDeparture = firstLeg.arrivalSeconds() + minTransferSeconds;
                    int latestSecondDepartureSeconds = Math.min(
                            absoluteLatestSecondDepartureSeconds,
                            firstLeg.arrivalSeconds() + options.maxTransferWaitMinutes() * 60
                    );
                    if (secondEarliestDeparture > latestSecondDepartureSeconds) {
                        continue;
                    }
                    secondLegQueries++;
                    List<DirectTransitLegData> secondLegs = dataAccess.findDirectLegs(
                            firstLeg.toAreaId(),
                            options.targetAreaId(),
                            options.date(),
                            secondEarliestDeparture,
                            latestSecondDepartureSeconds,
                            Math.max(1, options.maxResults() - results.size())
                    );
                    transferCandidates += secondLegs.size();
                    for (DirectTransitLegData secondLeg : secondLegs) {
                        int waitMinutes = Math.max(0,
                                (secondLeg.startDepartureSeconds() - firstLeg.arrivalSeconds()) / 60);
                        results.add(RouteCandidate.oneTransfer(firstLeg, secondLeg, waitMinutes));
                        if (results.size() >= options.maxResults()) {
                            break;
                        }
                    }
                }
            }

            results.sort(Comparator
                    .comparingInt(RouteCandidate::startDepartureSeconds)
                    .thenComparingInt(RouteCandidate::targetArrivalSeconds)
                    .thenComparingInt(RouteCandidate::totalDurationMinutes)
                    .thenComparing(RouteCandidate::routeSequence));
            if (results.size() > options.maxResults()) {
                results = List.copyOf(results.subList(0, options.maxResults()));
            }

            long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
            timeoutHit = timeoutHit || elapsedMs >= options.maxSearchMillis();
            printResult(options, startArea, targetArea, activeServiceIds.size(), directLegs.size(), directSufficient,
                    expandedNextLegs, transferCandidates, secondLegQueries, visitedSkipped, results, elapsedMs,
                    timeoutHit);
        }
    }

    private static ResolvedStopAreaData requireArea(
            TransitDataAccess dataAccess,
            String areaId,
            String role
    ) throws SQLException {
        ResolvedStopAreaData area = dataAccess.resolveStopArea(areaId);
        if (area == null) {
            throw new IllegalArgumentException(role + " StopArea not found: " + areaId);
        }
        return area;
    }

    private static boolean deadlineReached(long deadlineNanos) {
        return System.nanoTime() >= deadlineNanos;
    }

    private static void printResult(
            Options options,
            ResolvedStopAreaData startArea,
            ResolvedStopAreaData targetArea,
            int activeServiceCount,
            int directCount,
            boolean directSufficient,
            int expandedNextLegs,
            int transferCandidates,
            int secondLegQueries,
            int visitedSkipped,
            List<RouteCandidate> results,
            long elapsedMs,
            boolean timeoutHit
    ) {
        System.out.println("IXIT SQLite Hybrid Routing Diagnostics");
        System.out.println("database=" + options.database().toAbsolutePath());
        System.out.println("date=" + options.date());
        System.out.println("window=" + formatSeconds(options.fromSeconds()) + "-" + formatSeconds(options.toSeconds()));
        System.out.println("start_area=" + startArea.areaId() + " " + startArea.displayName()
                + " members=" + startArea.members().size());
        System.out.println("target_area=" + targetArea.areaId() + " " + targetArea.displayName()
                + " members=" + targetArea.members().size());
        System.out.println("active_services=" + activeServiceCount);
        System.out.println("max_next_legs_per_area=" + options.maxNextLegsPerArea());
        System.out.println("max_results=" + options.maxResults());
        System.out.println("max_search_millis=" + options.maxSearchMillis());
        System.out.println("min_transfer_minutes=" + options.minTransferMinutes());
        System.out.println("max_transfer_wait_minutes=" + options.maxTransferWaitMinutes());
        System.out.println("direct_sufficient_count=" + options.directSufficientCount());
        System.out.println("direct_count=" + directCount);
        System.out.println("direct_first_sufficient=" + directSufficient);
        System.out.println("expanded_next_legs=" + expandedNextLegs);
        System.out.println("second_leg_queries=" + secondLegQueries);
        System.out.println("transfer_candidates=" + transferCandidates);
        System.out.println("visited_skipped=" + visitedSkipped);
        System.out.println("results=" + results.size());
        System.out.println("elapsed_ms=" + elapsedMs);
        System.out.println("timeout_hit=" + timeoutHit);

        for (RouteCandidate candidate : results) {
            if (candidate.transferAreaId().isEmpty()) {
                System.out.println("- type=DIRECT routes=" + candidate.routeSequence()
                        + " start=" + formatSeconds(candidate.startDepartureSeconds())
                        + " target=" + formatSeconds(candidate.targetArrivalSeconds())
                        + " duration_minutes=" + candidate.totalDurationMinutes()
                        + " services=" + candidate.serviceReasons());
            } else {
                System.out.println("- type=ONE_TRANSFER routes=" + candidate.routeSequence()
                        + " start=" + formatSeconds(candidate.startDepartureSeconds())
                        + " transfer_area=" + candidate.transferAreaId()
                        + " transfer_name=" + candidate.transferAreaName()
                        + " transfer_arrival=" + formatSeconds(candidate.transferArrivalSeconds())
                        + " transfer_departure=" + formatSeconds(candidate.transferDepartureSeconds())
                        + " wait_minutes=" + candidate.transferWaitMinutes()
                        + " target=" + formatSeconds(candidate.targetArrivalSeconds())
                        + " duration_minutes=" + candidate.totalDurationMinutes()
                        + " services=" + candidate.serviceReasons());
            }
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

    private static String displayRoute(String shortName, String longName) {
        if (!isBlank(shortName)) {
            return shortName;
        }
        if (!isBlank(longName)) {
            return longName;
        }
        return "-";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private record VisitedKey(
            String areaId,
            String tripId,
            int depth
    ) {
    }

    private record RouteCandidate(
            String routeSequence,
            int startDepartureSeconds,
            int transferArrivalSeconds,
            int transferDepartureSeconds,
            int targetArrivalSeconds,
            int transferWaitMinutes,
            int totalDurationMinutes,
            String transferAreaId,
            String transferAreaName,
            String serviceReasons
    ) {
        static RouteCandidate direct(DirectTransitLegData leg) {
            return new RouteCandidate(
                    displayRoute(leg.routeShortName(), leg.routeLongName()),
                    leg.startDepartureSeconds(),
                    0,
                    0,
                    leg.targetArrivalSeconds(),
                    0,
                    Math.max(0, (leg.targetArrivalSeconds() - leg.startDepartureSeconds()) / 60),
                    "",
                    "",
                    leg.serviceActiveReason()
            );
        }

        static RouteCandidate oneTransfer(
                NextTransitLegData firstLeg,
                DirectTransitLegData secondLeg,
                int waitMinutes
        ) {
            return new RouteCandidate(
                    displayRoute(firstLeg.routeShortName(), firstLeg.routeLongName())
                            + " -> "
                            + displayRoute(secondLeg.routeShortName(), secondLeg.routeLongName()),
                    firstLeg.departureSeconds(),
                    firstLeg.arrivalSeconds(),
                    secondLeg.startDepartureSeconds(),
                    secondLeg.targetArrivalSeconds(),
                    waitMinutes,
                    Math.max(0, (secondLeg.targetArrivalSeconds() - firstLeg.departureSeconds()) / 60),
                    firstLeg.toAreaId(),
                    firstLeg.toAreaName(),
                    firstLeg.serviceActiveReason() + " -> " + secondLeg.serviceActiveReason()
            );
        }
    }

    private record Options(
            Path database,
            LocalDate date,
            String startAreaId,
            String targetAreaId,
            int fromSeconds,
            int toSeconds,
            int maxNextLegsPerArea,
            int maxResults,
            int maxSearchMillis,
            int minTransferMinutes,
            int maxTransferWaitMinutes,
            int secondLegWindowMinutes,
            int directSufficientCount
    ) {
        static Options parse(String[] args) {
            Map<String, String> values = new HashMap<>();
            for (int index = 0; index < args.length; index++) {
                String arg = args[index];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
                if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for " + arg);
                }
                values.put(arg.substring(2), args[++index]);
            }

            Path database = Path.of(values.getOrDefault("database", "build/gtfs-de-full-core-v0_5.sqlite"));
            LocalDate date = LocalDate.parse(values.getOrDefault("date", LocalDate.now().toString()));
            String startAreaId = required(values, "start-area");
            String targetAreaId = required(values, "target-area");
            int fromSeconds = parseTimeSeconds(values.getOrDefault("from", LocalTime.now().withSecond(0).toString()));
            int toSeconds = parseTimeSeconds(values.getOrDefault("to", "27:00"));
            int maxNextLegsPerArea = Integer.parseInt(values.getOrDefault(
                    "max-next-legs-per-area",
                    String.valueOf(DEFAULT_MAX_NEXT_LEGS_PER_AREA)
            ));
            int maxResults = Integer.parseInt(values.getOrDefault("max-results", String.valueOf(DEFAULT_MAX_RESULTS)));
            int maxSearchMillis = Integer.parseInt(values.getOrDefault(
                    "max-search-millis",
                    String.valueOf(DEFAULT_MAX_SEARCH_MILLIS)
            ));
            int minTransferMinutes = Integer.parseInt(values.getOrDefault(
                    "min-transfer-minutes",
                    String.valueOf(DEFAULT_MIN_TRANSFER_MINUTES)
            ));
            int maxTransferWaitMinutes = Integer.parseInt(values.getOrDefault(
                    "max-transfer-wait-minutes",
                    String.valueOf(DEFAULT_MAX_TRANSFER_WAIT_MINUTES)
            ));
            int secondLegWindowMinutes = Integer.parseInt(values.getOrDefault(
                    "second-leg-window-minutes",
                    String.valueOf(DEFAULT_SECOND_LEG_WINDOW_MINUTES)
            ));
            int directSufficientCount = Integer.parseInt(values.getOrDefault(
                    "direct-sufficient-count",
                    String.valueOf(DEFAULT_DIRECT_SUFFICIENT_COUNT)
            ));

            if (toSeconds < fromSeconds) {
                throw new IllegalArgumentException("--to must not be before --from");
            }
            if (maxNextLegsPerArea < 1 || maxNextLegsPerArea > 500) {
                throw new IllegalArgumentException("--max-next-legs-per-area must be between 1 and 500");
            }
            if (maxResults < 1 || maxResults > 100) {
                throw new IllegalArgumentException("--max-results must be between 1 and 100");
            }
            if (maxSearchMillis < 100 || maxSearchMillis > 60_000) {
                throw new IllegalArgumentException("--max-search-millis must be between 100 and 60000");
            }
            if (minTransferMinutes < 0 || minTransferMinutes > 120) {
                throw new IllegalArgumentException("--min-transfer-minutes must be between 0 and 120");
            }
            if (maxTransferWaitMinutes < minTransferMinutes || maxTransferWaitMinutes > 720) {
                throw new IllegalArgumentException("--max-transfer-wait-minutes must be between min-transfer-minutes and 720");
            }
            if (secondLegWindowMinutes < 0 || secondLegWindowMinutes > 720) {
                throw new IllegalArgumentException("--second-leg-window-minutes must be between 0 and 720");
            }
            if (directSufficientCount < 1 || directSufficientCount > maxResults) {
                throw new IllegalArgumentException("--direct-sufficient-count must be between 1 and max-results");
            }

            return new Options(database, date, startAreaId, targetAreaId, fromSeconds, toSeconds,
                    maxNextLegsPerArea, maxResults, maxSearchMillis, minTransferMinutes,
                    maxTransferWaitMinutes, secondLegWindowMinutes, directSufficientCount);
        }

        private static String required(Map<String, String> values, String key) {
            String value = values.get(key);
            if (isBlank(value)) {
                throw new IllegalArgumentException("Missing required option --" + key);
            }
            return value;
        }
    }
}
