package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopFootpath;

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
    private static final double DETOUR_FACTOR = 1.35;
    private static final double WALKING_METERS_PER_SECOND = 1.2;
    private static final int WAYFINDING_BUFFER_SECONDS = 60;
    private static final int MINIMUM_TRANSFER_SECONDS = 120;

    private final Map<String, List<Stop>> boardingStopsByArea = new LinkedHashMap<>();

    public StopFootpathBuilder(List<Stop> stops) {
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
                for (Stop to : members) {
                    if (from.stopId().equals(to.stopId())) {
                        continue;
                    }
                    sequence++;
                    Integer distance = distanceMeters(from, to);
                    boolean traversable = distance != null && distance <= MAX_ESTIMATED_TRAVERSABLE_METERS;
                    Integer seconds = distance == null ? null : estimatedSeconds(distance);
                    String quality = quality(distance);
                    if (distance == null) {
                        areaUnknown = true;
                    } else {
                        areaMaxDistance = Math.max(areaMaxDistance, distance);
                    }
                    StopFootpath footpath = new StopFootpath(
                            "same_area_footpath_" + sequence,
                            entry.getKey(),
                            from.stopId(),
                            to.stopId(),
                            distance,
                            seconds,
                            traversable,
                            quality,
                            distance == null ? "UNKNOWN" : DISTANCE_MODEL,
                            distance == null ? "UNKNOWN" : TIME_MODEL,
                            "SAME_STOP_AREA_GEOMETRY",
                            explanation(distance, traversable)
                    );
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

    private static int estimatedSeconds(int distanceMeters) {
        int estimate = (int) Math.ceil(distanceMeters * DETOUR_FACTOR / WALKING_METERS_PER_SECOND)
                + WAYFINDING_BUFFER_SECONDS;
        return Math.max(MINIMUM_TRANSFER_SECONDS, estimate);
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
