package de.ixit.gtfs;

import de.ixit.gtfs.model.AreaRouteServiceSummary;
import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.Trip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AreaRouteServiceSummaryBuilder {
    private final Map<String, String> areaIdByStopId = new HashMap<>();
    private final Map<String, TripRoute> routeByTripId = new HashMap<>();
    private final Map<Key, Accumulator> accumulators = new HashMap<>();

    public AreaRouteServiceSummaryBuilder(List<Stop> stops, List<Route> routes, List<Trip> trips) {
        for (Stop stop : stops) {
            areaIdByStopId.put(stop.stopId(), StopAreaBuilder.areaIdFor(stop));
        }

        Map<String, RouteMeta> routeMetaById = new HashMap<>();
        for (Route route : routes) {
            routeMetaById.put(route.routeId(), new RouteMeta(
                    route.routeId(),
                    route.routeType(),
                    lineLabel(route)
            ));
        }

        for (Trip trip : trips) {
            RouteMeta routeMeta = routeMetaById.get(trip.routeId());
            if (routeMeta != null) {
                routeByTripId.put(trip.tripId(), new TripRoute(
                        routeMeta.routeId(),
                        routeMeta.routeType(),
                        routeMeta.lineLabel()
                ));
            }
        }
    }

    public void observeStopTime(String tripId, String stopId, int stopSequence) {
        String areaId = areaIdByStopId.get(stopId);
        TripRoute tripRoute = routeByTripId.get(tripId);
        if (areaId == null || tripRoute == null) {
            return;
        }
        Key key = new Key(areaId, tripRoute.routeId());
        Accumulator accumulator = accumulators.computeIfAbsent(key, ignored -> new Accumulator(
                areaId,
                tripRoute.routeId(),
                tripRoute.routeType(),
                tripRoute.lineLabel()
        ));
        accumulator.observe(tripId);
    }

    public AreaRouteServiceSummaryBuildResult build() {
        List<AreaRouteServiceSummary> summaries = new ArrayList<>(accumulators.size());
        for (Accumulator accumulator : accumulators.values()) {
            summaries.add(accumulator.toSummary());
        }
        summaries.sort(Comparator
                .comparing(AreaRouteServiceSummary::areaId)
                .thenComparing(summary -> nullSafe(summary.routeType()))
                .thenComparing(summary -> nullSafe(summary.lineLabel()))
                .thenComparing(AreaRouteServiceSummary::routeId));

        accumulators.clear();
        areaIdByStopId.clear();
        routeByTripId.clear();
        return new AreaRouteServiceSummaryBuildResult(Collections.unmodifiableList(summaries));
    }

    private static String lineLabel(Route route) {
        if (route.routeShortName() != null && !route.routeShortName().isBlank()) {
            return route.routeShortName().trim();
        }
        if (route.routeLongName() != null && !route.routeLongName().isBlank()) {
            return route.routeLongName().trim();
        }
        return route.routeId();
    }

    private static int nullSafe(Integer value) {
        return value == null ? Integer.MAX_VALUE : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private record Key(String areaId, String routeId) {
    }

    private record RouteMeta(String routeId, Integer routeType, String lineLabel) {
    }

    private record TripRoute(String routeId, Integer routeType, String lineLabel) {
    }

    private static final class Accumulator {
        private final String areaId;
        private final String routeId;
        private final Integer routeType;
        private final String lineLabel;
        private int stopTimeCount;
        private int tripCount;
        private String lastTripId;

        private Accumulator(String areaId, String routeId, Integer routeType, String lineLabel) {
            this.areaId = areaId;
            this.routeId = routeId;
            this.routeType = routeType;
            this.lineLabel = lineLabel;
        }

        private void observe(String tripId) {
            stopTimeCount++;
            if (!tripId.equals(lastTripId)) {
                tripCount++;
                lastTripId = tripId;
            }
        }

        private AreaRouteServiceSummary toSummary() {
            return new AreaRouteServiceSummary(
                    areaId,
                    routeId,
                    routeType,
                    lineLabel,
                    stopTimeCount,
                    tripCount
            );
        }
    }

    public record AreaRouteServiceSummaryBuildResult(List<AreaRouteServiceSummary> summaries) {
    }
}
