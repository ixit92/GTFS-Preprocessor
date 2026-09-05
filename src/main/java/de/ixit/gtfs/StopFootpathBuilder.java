package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopFootpath;
import de.ixit.gtfs.model.Pathway;
import de.ixit.gtfs.model.TransferRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class StopFootpathBuilder {
    public static final int MAX_ESTIMATED_TRAVERSABLE_METERS = 400;
    public static final int EXTREME_AREA_SPREAD_METERS = 700;
    public static final String DISTANCE_MODEL = "STRAIGHT_LINE_LOWER_BOUND";
    public static final String TIME_MODEL = "detour_1.35_speed_1.2mps_plus_60s_min_120s";
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final Map<String, List<Stop>> boardingStopsByArea = new LinkedHashMap<>();
    private final StationPathwayGraph pathways;
    private final StopTransferConstraints constraints;

    public StopFootpathBuilder(List<Stop> stops) {
        this(stops, List.of(), List.of());
    }

    public StopFootpathBuilder(List<Stop> stops, List<Pathway> paths, List<TransferRule> rules) {
        pathways = new StationPathwayGraph(stops, paths);
        constraints = new StopTransferConstraints(stops, rules);
        for (Stop stop : stops) {
            if (!isBoardingStop(stop)) {
                continue;
            }
            boardingStopsByArea
                    .computeIfAbsent(StopAreaBuilder.areaIdFor(stop), ignored -> new ArrayList<>())
                    .add(stop);
        }
    }

    public StopFootpathStats writeTo(Consumer<StopFootpath> consumer) {
        StatsAccumulator stats = new StatsAccumulator();
        long sequence = 0;
        for (Map.Entry<String, List<Stop>> entry : boardingStopsByArea.entrySet()) {
            List<Stop> members = entry.getValue();
            if (members.size() <= 1) {
                continue;
            }
            stats.multiStopAreas++;
            boolean areaUnknown = false;
            int areaMaxDistance = 0;
            for (Stop from : members) {
                StationPathwayGraph.Paths routes = pathways.covers(entry.getKey()) ? pathways.pathsFrom(from.stopId()) : null;
                for (Stop to : members) {
                    if (from.stopId().equals(to.stopId())) {
                        continue;
                    }
                    sequence++;
                    Integer distance = distanceMeters(from, to);
                    if (distance == null) {
                        areaUnknown = true;
                    } else {
                        areaMaxDistance = Math.max(areaMaxDistance, distance);
                    }
                    StopFootpath footpath = footpath(sequence, entry.getKey(), from, to, distance, routes);
                    consumer.accept(footpath);
                    stats.accept(footpath);
                }
            }
            if (areaUnknown) {
                stats.areasWithUnknownPairs++;
            }
            if (areaMaxDistance > MAX_ESTIMATED_TRAVERSABLE_METERS) {
                stats.oversizedAreas++;
                stats.addSample(entry.getKey() + ":max_pair_distance=" + areaMaxDistance + "m");
            }
            if (areaMaxDistance > EXTREME_AREA_SPREAD_METERS) {
                stats.extremeAreas++;
            }
            stats.maxAreaPairDistanceMeters = Math.max(stats.maxAreaPairDistanceMeters, areaMaxDistance);
        }
        boardingStopsByArea.clear();
        return stats.toStats();
    }

    public int unusablePathwayRows() {
        return pathways.unusableRows();
    }

    private StopFootpath footpath(long sequence, String areaId, Stop from, Stop to, Integer distance,
                                  StationPathwayGraph.Paths routes) {
        StopTransferConstraints.Constraint constraint = constraints.between(from.stopId(), to.stopId());
        StationPathwayGraph.Evidence evidence = routes == null ? null : routes.to(to.stopId());
        boolean mapped = evidence != null;
        boolean blockedByGraph = routes != null && !mapped;
        Integer walkSeconds = null;
        if (mapped) walkSeconds = evidence.walkSeconds();
        else if (!blockedByGraph && distance != null) walkSeconds = WalkTimeModel.estimatedWalkSeconds(distance);
        Integer seconds = walkSeconds == null ? null : WalkTimeModel.minimumTransferSeconds(walkSeconds, constraint.minimumSeconds());
        boolean traversable = !constraint.blocked() && !blockedByGraph
                && (mapped || distance != null && distance <= MAX_ESTIMATED_TRAVERSABLE_METERS);
        String quality = constraint.blocked() ? "BLOCKED" : blockedByGraph ? "UNKNOWN"
                : mapped ? evidence.estimated() ? "ESTIMATED" : "FEED_PROVIDED" : quality(distance);
        return new StopFootpath("same_area_footpath_" + sequence, areaId, from.stopId(), to.stopId(),
                mapped ? evidence.lengthMeters() : distance, seconds, traversable, quality,
                mapped ? evidence.lengthMeters() == null ? "GTFS_PATHWAY_TIME_ONLY" : "GTFS_PATHWAY_LENGTH"
                        : distance == null ? "UNKNOWN" : DISTANCE_MODEL,
                mapped ? evidence.estimated() ? "PATHWAY_MODE_ESTIMATE_PLUS_BUFFER" : "PATHWAY_TRAVERSAL_TIME_PLUS_BUFFER"
                        : blockedByGraph || distance == null ? "UNKNOWN" : TIME_MODEL,
                routes == null ? "SAME_STOP_AREA_GEOMETRY" : "GTFS_PATHWAYS",
                constraint.blocked() ? "Unscoped GTFS prohibition or invalid mandatory minimum blocks this generic transfer."
                        : blockedByGraph ? "No usable directed path in the supplied station graph; geometry fallback is disabled."
                        : mapped ? "Directed station walk; buffer applied once; GTFS minimum is a lower bound. Accessibility and scoped rules require consumer validation."
                        : explanation(distance, traversable),
                walkSeconds, walkSeconds == null ? null : WalkTimeModel.BUFFER_SECONDS,
                constraint.minimumSeconds(), mapped ? evidence.pathwayIds() : List.of(), mapped ? evidence.modes() : 0);
    }

    private static boolean isBoardingStop(Stop stop) {
        return stop.locationType() == null || stop.locationType() == 0;
    }

    private static Integer distanceMeters(Stop from, Stop to) {
        if (!hasValidCoordinates(from) || !hasValidCoordinates(to)) {
            return null;
        }
        double lat1 = Math.toRadians(from.stopLat());
        double lat2 = Math.toRadians(to.stopLat());
        double deltaLat = Math.toRadians(to.stopLat() - from.stopLat());
        double deltaLon = Math.toRadians(to.stopLon() - from.stopLon());
        double sinLat = Math.sin(deltaLat / 2.0);
        double sinLon = Math.sin(deltaLon / 2.0);
        double haversine = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double centralAngle = 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
        return (int) Math.round(EARTH_RADIUS_METERS * centralAngle);
    }

    private static boolean hasValidCoordinates(Stop stop) {
        return stop.stopLat() != null
                && stop.stopLon() != null
                && stop.stopLat() >= -90.0
                && stop.stopLat() <= 90.0
                && stop.stopLon() >= -180.0
                && stop.stopLon() <= 180.0;
    }

    private static String quality(Integer distanceMeters) {
        if (distanceMeters == null || distanceMeters > MAX_ESTIMATED_TRAVERSABLE_METERS) {
            return "UNKNOWN";
        }
        if (distanceMeters <= 80) {
            return "GOOD";
        }
        if (distanceMeters <= 200) {
            return "ESTIMATED";
        }
        return "LOW";
    }

    private static String explanation(Integer distanceMeters, boolean traversable) {
        if (distanceMeters == null) {
            return "No usable coordinates; area membership does not prove a walking path.";
        }
        if (!traversable) {
            return "Straight-line separation exceeds the conservative same-area threshold; routing must not assume a footpath.";
        }
        return "Conservative same-area estimate from concrete stop coordinates; not a surveyed pedestrian path.";
    }

    private static final class StatsAccumulator {
        private long footpathCount;
        private long traversableCount;
        private long unknownCount;
        private int multiStopAreas;
        private int areasWithUnknownPairs;
        private int oversizedAreas;
        private int extremeAreas;
        private int maxAreaPairDistanceMeters;
        private final Map<String, Long> qualityCounts = new LinkedHashMap<>();
        private final Set<String> samples = new LinkedHashSet<>();

        private void accept(StopFootpath footpath) {
            footpathCount++;
            if (footpath.traversable()) {
                traversableCount++;
            } else {
                unknownCount++;
            }
            qualityCounts.merge(footpath.quality(), 1L, Long::sum);
        }

        private void addSample(String sample) {
            if (samples.size() < 10) {
                samples.add(sample);
            }
        }

        private StopFootpathStats toStats() {
            return new StopFootpathStats(
                    footpathCount,
                    traversableCount,
                    unknownCount,
                    multiStopAreas,
                    areasWithUnknownPairs,
                    oversizedAreas,
                    extremeAreas,
                    maxAreaPairDistanceMeters,
                    Map.copyOf(qualityCounts),
                    List.copyOf(samples)
            );
        }
    }

    public record StopFootpathStats(
            long footpathCount,
            long traversableCount,
            long unknownCount,
            int multiStopAreas,
            int areasWithUnknownPairs,
            int oversizedAreas,
            int extremeAreas,
            int maxAreaPairDistanceMeters,
            Map<String, Long> qualityCounts,
            List<String> samples
    ) {
    }
}
