package de.ixit.gtfs;

import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.RouteAxis;
import de.ixit.gtfs.model.RouteAxisStop;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.Trip;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class RouteAxisBuilder {
    private final Map<String, Route> routesById = new LinkedHashMap<>();

    public RouteAxisBuilder(List<Stop> stops, List<Route> routes, List<Trip> trips) {
        for (Route route : routes) {
            routesById.put(route.routeId(), route);
        }
        // Stops, trips and stop_times are intentionally not retained here.
    }

    public void observeStopTime(String tripId, String stopId, int stopSequence) {
        // Keep stop_times heap-stable; RouteAxis is built from SQLite after import.
    }

    public RouteAxisBuildResult build() {
        throw new IllegalStateException("RouteAxisBuilder.build() is disabled; use buildFromDatabase(Path).");
    }

    public RouteAxisBuildResult buildFromDatabase(Path databasePath) throws SQLException {
        List<RouteAxis> axes = new ArrayList<>();
        List<RouteAxisStop> axisStops = new ArrayList<>();
        RouteAxisStats stats = streamFromDatabase(databasePath, (axis, sequence) -> {
            axes.add(axis);
            for (int index = 0; index < sequence.size(); index++) {
                axisStops.add(new RouteAxisStop(axis.axisId(), index, sequence.get(index)));
            }
        }, null);
        return new RouteAxisBuildResult(List.copyOf(axes), List.copyOf(axisStops), stats);
    }

    @FunctionalInterface
    public interface AxisConsumer {
        void accept(RouteAxis axis, List<String> sequence) throws SQLException;
    }

    public RouteAxisStats streamFromDatabase(Path databasePath, AxisConsumer consumer,
                                             GtfsCsvReader.ProgressListener progress) throws SQLException {
        Map<AxisKey, AxisAccumulator> accumulators = new LinkedHashMap<>();
        Set<String> routesWithoutUsableSequence = new LinkedHashSet<>();
        Counter tripsWithoutUsableSequence = new Counter();
        Counter unmappedStopTimeCount = new Counter();
        Set<String> unmappedStopSamples = new LinkedHashSet<>();

        streamTripSequences(databasePath, accumulators, routesWithoutUsableSequence, tripsWithoutUsableSequence, unmappedStopTimeCount, unmappedStopSamples, progress);
        int tripsWithoutStopTimes = tripsWithoutStopTimes(databasePath, routesWithoutUsableSequence);

        List<RouteAxis> axes = new ArrayList<>();
        List<AxisAccumulator> sortedAccumulators = new ArrayList<>(accumulators.values());
        accumulators.clear();
        sortedAccumulators.sort(Comparator.comparing((AxisAccumulator accumulator) -> accumulator.key.routeId())
                .thenComparing(accumulator -> nullSafe(accumulator.key.directionId()))
                .thenComparing(AxisAccumulator::representativeTripId));

        int ordinal = 1;
        int axisStopCount = 0;
        for (AxisAccumulator accumulator : sortedAccumulators) {
            Route route = routesById.get(accumulator.key.routeId());
            String axisId = axisId(accumulator.key, ordinal++);
            List<String> sequence = accumulator.key.sequence();
            RouteAxis axis = new RouteAxis(
                    axisId,
                    accumulator.key.routeId(),
                    accumulator.key.directionId(),
                    accumulator.representativeTripId(),
                    accumulator.tripCount,
                    sequence.size(),
                    sequence.getFirst(),
                    sequence.getLast(),
                    route == null ? null : route.routeShortName(),
                    route == null ? null : route.routeLongName(),
                    route == null ? null : route.routeType(),
                    "exact_sequence_group; trips=" + accumulator.tripCount + "; stop_areas=" + sequence.size()
            );
            axes.add(axis);
            consumer.accept(axis, sequence);
            axisStopCount = Math.addExact(axisStopCount, sequence.size());
        }

        return RouteAxisStats.from(
                axes,
                axisStopCount,
                routesWithoutUsableSequence,
                tripsWithoutStopTimes,
                tripsWithoutUsableSequence.value,
                unmappedStopTimeCount.value,
                unmappedStopSamples
        );
    }

    private static void streamTripSequences(
            Path databasePath,
            Map<AxisKey, AxisAccumulator> accumulators,
            Set<String> routesWithoutUsableSequence,
            Counter tripsWithoutUsableSequence,
            Counter unmappedStopTimeCount,
            Set<String> unmappedStopSamples,
            GtfsCsvReader.ProgressListener progress
    ) throws SQLException {
        // Share retained identifiers across exact sequences; never use the global intern pool.
        Map<String, String> areaIds = new HashMap<>();
        Map<String, String> routeIds = new HashMap<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT st.trip_id,
                            t.route_id,
                            t.direction_id,
                            st.stop_sequence,
                            sam.area_id
                     FROM stop_times st
                     JOIN trips t ON t.trip_id = st.trip_id
                     LEFT JOIN stop_area_members sam ON sam.stop_id = st.stop_id
                     ORDER BY st.trip_id, st.stop_sequence
                     """)) {
            TripSequence current = null;
            long rows = 0;
            while (resultSet.next()) {
                if (progress != null) progress.onRowsRead(++rows);
                String tripId = resultSet.getString("trip_id");
                if (current == null || !current.tripId().equals(tripId)) {
                    finalizeTrip(current, accumulators, routesWithoutUsableSequence, tripsWithoutUsableSequence);
                    current = new TripSequence(
                            tripId,
                            routeIds.computeIfAbsent(resultSet.getString("route_id"), id -> id),
                            resultSet.getString("direction_id")
                    );
                }

                String areaId = resultSet.getString("area_id");
                if (areaId == null || areaId.isBlank()) {
                    unmappedStopTimeCount.value++;
                    if (unmappedStopSamples.size() < 5) {
                        unmappedStopSamples.add(tripId + "->sequence_" + resultSet.getInt("stop_sequence"));
                    }
                    continue;
                }
                current.addArea(areaIds.computeIfAbsent(areaId, id -> id));
            }
            finalizeTrip(current, accumulators, routesWithoutUsableSequence, tripsWithoutUsableSequence);
        }
    }

    private static void finalizeTrip(
            TripSequence trip,
            Map<AxisKey, AxisAccumulator> accumulators,
            Set<String> routesWithoutUsableSequence,
            Counter tripsWithoutUsableSequence
    ) {
        if (trip == null) {
            return;
        }
        if (trip.sequence().isEmpty()) {
            tripsWithoutUsableSequence.value++;
            routesWithoutUsableSequence.add(trip.routeId());
            return;
        }

        AxisKey key = new AxisKey(trip.routeId(), trip.directionId(), trip.sequence());
        accumulators.computeIfAbsent(key, ignored -> new AxisAccumulator(key, trip.tripId()))
                .addTrip();
    }

    private static int tripsWithoutStopTimes(Path databasePath, Set<String> routesWithoutUsableSequence) throws SQLException {
        int count = 0;
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT t.route_id
                     FROM trips t
                     LEFT JOIN (SELECT DISTINCT trip_id FROM stop_times) st ON st.trip_id = t.trip_id
                     WHERE st.trip_id IS NULL
                     """)) {
            while (resultSet.next()) {
                count++;
                routesWithoutUsableSequence.add(resultSet.getString("route_id"));
            }
        }
        return count;
    }

    private static String axisId(AxisKey key, int ordinal) {
        String direction = key.directionId() == null || key.directionId().isBlank() ? "none" : key.directionId();
        String hash = Integer.toHexString(Objects.hash(key.routeId(), direction, key.sequence()));
        return "axis_" + sanitize(key.routeId()) + "_" + sanitize(direction) + "_" + ordinal + "_" + hash;
    }

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9]+", "_");
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static final class Counter {
        private int value;
    }

    private static final class TripSequence {
        private final String tripId;
        private final String routeId;
        private final String directionId;
        private final List<String> sequence = new ArrayList<>();
        private String previousAreaId;

        private TripSequence(String tripId, String routeId, String directionId) {
            this.tripId = tripId;
            this.routeId = routeId;
            this.directionId = directionId;
        }

        private void addArea(String areaId) {
            if (!areaId.equals(previousAreaId)) {
                sequence.add(areaId);
                previousAreaId = areaId;
            }
        }

        private String tripId() {
            return tripId;
        }

        private String routeId() {
            return routeId;
        }

        private String directionId() {
            return directionId;
        }

        private List<String> sequence() {
            return sequence;
        }
    }

    private record AxisKey(String routeId, String directionId, List<String> sequence) {
        private AxisKey {
            sequence = List.copyOf(sequence);
        }
    }

    private static final class AxisAccumulator {
        private final AxisKey key;
        private final String representativeTripId;
        private int tripCount;

        private AxisAccumulator(AxisKey key, String representativeTripId) {
            this.key = key;
            this.representativeTripId = representativeTripId;
        }

        private void addTrip() {
            tripCount++;
        }

        private String representativeTripId() {
            return representativeTripId;
        }
    }

    public record RouteAxisBuildResult(List<RouteAxis> axes, List<RouteAxisStop> axisStops, RouteAxisStats stats) {
    }

    public record RouteAxisStats(
            int axisCount,
            int axisStopCount,
            List<RouteAxis> topAxesByTripCount,
            Map<String, Integer> axisCountsByRoute,
            List<String> routesWithoutUsableSequence,
            int tripsWithoutStopTimes,
            int tripsWithoutUsableSequence,
            int unmappedStopTimeCount,
            List<String> unmappedStopSamples,
            List<RouteAxis> shortAxes
    ) {
        public static RouteAxisStats from(List<RouteAxis> axes, List<RouteAxisStop> axisStops,
                Set<String> routesWithoutUsableSequence, int tripsWithoutStopTimes,
                int tripsWithoutUsableSequence, int unmappedStopTimeCount, Set<String> unmappedStopSamples) {
            return from(axes, axisStops.size(), routesWithoutUsableSequence, tripsWithoutStopTimes,
                    tripsWithoutUsableSequence, unmappedStopTimeCount, unmappedStopSamples);
        }

        public static RouteAxisStats from(
                List<RouteAxis> axes,
                int axisStopCount,
                Set<String> routesWithoutUsableSequence,
                int tripsWithoutStopTimes,
                int tripsWithoutUsableSequence,
                int unmappedStopTimeCount,
                Set<String> unmappedStopSamples
        ) {
            List<RouteAxis> topAxes = axes.stream()
                    .sorted(Comparator.comparingInt(RouteAxis::tripCount).reversed()
                            .thenComparing(RouteAxis::routeId)
                            .thenComparing(RouteAxis::axisId))
                    .limit(10)
                    .toList();
            Map<String, Integer> axisCountsByRoute = axes.stream()
                    .collect(Collectors.toMap(RouteAxis::routeId, ignored -> 1, Integer::sum, LinkedHashMap::new));
            List<RouteAxis> shortAxes = axes.stream()
                    .filter(axis -> axis.stopCount() < 2)
                    .sorted(Comparator.comparing(RouteAxis::routeId).thenComparing(RouteAxis::axisId))
                    .toList();
            return new RouteAxisStats(
                    axes.size(),
                    axisStopCount,
                    List.copyOf(topAxes),
                    Map.copyOf(axisCountsByRoute),
                    List.copyOf(routesWithoutUsableSequence),
                    tripsWithoutStopTimes,
                    tripsWithoutUsableSequence,
                    unmappedStopTimeCount,
                    List.copyOf(unmappedStopSamples),
                    List.copyOf(shortAxes)
            );
        }

        public List<Map.Entry<String, Integer>> routesWithMostAxes(int limit) {
            return axisCountsByRoute.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                    .limit(limit)
                    .toList();
        }
    }
}
