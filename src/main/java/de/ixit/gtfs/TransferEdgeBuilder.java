package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.TransferEdge;
import de.ixit.gtfs.model.TransferRule;
import de.ixit.gtfs.model.Pathway;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

public final class TransferEdgeBuilder {
    private static final int DEFAULT_TRANSFER_SECONDS = 180;
    private static final int MAX_DISTANCE_HEURISTIC_METERS = 700;
    private static final int MAX_NEARBY_CANDIDATES_PER_AREA = 32;
    private static final double GRID_SIZE_DEGREES = 0.01;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final Map<String, Stop> stopById = new HashMap<>();
    private final Map<String, StopArea> areaById = new HashMap<>();
    private final Map<String, List<Stop>> boardingStopsByArea = new HashMap<>();
    private final StationPathwayGraph pathways;
    private StopTransferConstraints constraints;
    private String pathSource;
    private StationPathwayGraph.Paths sourcePaths;

    public TransferEdgeBuilder(List<Stop> stops, List<StopArea> stopAreas) {
        this(stops, stopAreas, List.of());
    }

    public TransferEdgeBuilder(List<Stop> stops, List<StopArea> stopAreas, List<Pathway> paths) {
        pathways = new StationPathwayGraph(stops, paths);
        for (Stop stop : stops) {
            stopById.put(stop.stopId(), stop);
            if (stop.locationType() == null || stop.locationType() == 0) {
                boardingStopsByArea
                        .computeIfAbsent(StopAreaBuilder.areaIdFor(stop), ignored -> new ArrayList<>())
                        .add(stop);
            }
        }
        for (StopArea stopArea : stopAreas) {
            areaById.put(stopArea.areaId(), stopArea);
        }
    }

    public TransferEdgeBuildResult build(List<TransferRule> transferRules) {
        List<TransferEdge> edges = new ArrayList<>();
        TransferEdgeStats stats = writeTo(transferRules, edges::add);
        edges.sort(Comparator.comparing(TransferEdge::transferEdgeId));
        return new TransferEdgeBuildResult(List.copyOf(edges), stats);
    }

    public TransferEdgeStats writeTo(List<TransferRule> transferRules, Consumer<TransferEdge> consumer) {
        constraints = new StopTransferConstraints(new ArrayList<>(stopById.values()), transferRules);
        TransferEdgeStatsAccumulator stats = new TransferEdgeStatsAccumulator();
        Set<AreaPair> explicitPedestrianPairs = new HashSet<>();
        Set<AreaPair> prohibitedStopPairs = new HashSet<>();
        Set<String> sameAreaCandidates = new LinkedHashSet<>();

        for (TransferRule rule : transferRules) {
            if ("GTFS_TRANSFERS".equals(rule.source())
                    && rule.pedestrianUsable()
                    && "STOP".equals(rule.scopeType())) {
                TransferEdge edge = gtfsPedestrianEdge(rule);
                consumer.accept(edge);
                stats.accept(edge);
                explicitPedestrianPairs.add(new AreaPair(rule.fromAreaId(), rule.toAreaId()));
            } else if ("GTFS_TRANSFERS".equals(rule.source())
                    && "PROHIBITED".equals(rule.transferSemantic())
                    && "STOP".equals(rule.scopeType())) {
                prohibitedStopPairs.add(new AreaPair(rule.fromAreaId(), rule.toAreaId()));
            } else if ("SAME_STOP_AREA".equals(rule.source())) {
                sameAreaCandidates.add(rule.fromAreaId());
            }
        }

        for (StopArea stopArea : areaById.values()) {
            if (stopArea.stopCount() > 1) {
                sameAreaCandidates.add(stopArea.areaId());
            }
        }
        for (String areaId : sameAreaCandidates) {
            TransferEdge edge = sameAreaCandidate(areaId);
            if (edge != null) {
                consumer.accept(edge);
                stats.accept(edge);
            }
        }

        explicitPedestrianPairs.addAll(prohibitedStopPairs);
        addDistanceHeuristicEdges(explicitPedestrianPairs, edge -> {
            consumer.accept(edge);
            stats.accept(edge);
        });

        TransferEdgeStats result = stats.toStats();
        explicitPedestrianPairs.clear();
        prohibitedStopPairs.clear();
        sameAreaCandidates.clear();
        stopById.clear();
        areaById.clear();
        boardingStopsByArea.clear();
        pathways.clear();
        sourcePaths = null;
        constraints = null;
        System.gc();
        return result;
    }

    private TransferEdge gtfsPedestrianEdge(TransferRule rule) {
        StopTransferConstraints.Constraint constraint = constraints.between(rule.fromStopId(), rule.toStopId());
        Integer distance = distanceBetweenStops(rule.fromStopId(), rule.toStopId());
        boolean covered = rule.fromAreaId().equals(rule.toAreaId()) && pathways.covers(rule.fromAreaId());
        StationPathwayGraph.Evidence evidence = null;
        if (covered && !rule.fromStopId().equals(rule.toStopId())) {
            if (!rule.fromStopId().equals(pathSource)) {
                pathSource = rule.fromStopId();
                sourcePaths = pathways.pathsFrom(pathSource);
            }
            evidence = sourcePaths.to(rule.toStopId());
        }
        boolean traversable = !constraint.blocked()
                && (!covered || evidence != null || rule.fromStopId().equals(rule.toStopId()));
        Integer minimum = constraint.minimumSeconds();
        int seconds = evidence != null ? WalkTimeModel.minimumTransferSeconds(evidence.walkSeconds(), minimum)
                : distance != null ? WalkTimeModel.minimumTransferSeconds(WalkTimeModel.estimatedWalkSeconds(distance), minimum)
                : Math.max(DEFAULT_TRANSFER_SECONDS, minimum == null ? 0 : minimum);
        int minutes = (int) (((long) seconds + 59) / 60);
        if (evidence != null) distance = evidence.lengthMeters();
        return new TransferEdge(
                "edge_gtfs_" + rule.rawTransferId(),
                rule.rawTransferId(),
                rule.fromAreaId(),
                rule.toAreaId(),
                rule.fromStopId(),
                rule.toStopId(),
                distance,
                seconds,
                minutes,
                traversable,
                "GTFS_PEDESTRIAN_TRANSFER",
                rule.transferSemantic(),
                rule.scopeType(),
                evidence != null ? distance == null ? "GTFS_PATHWAY_TIME_ONLY" : "GTFS_PATHWAY_LENGTH"
                        : distance == null ? "GTFS_TIME_ONLY" : "GTFS_TIME_WITH_STOP_COORDINATE_DISTANCE",
                traversable ? qualityForSeconds(seconds) : "BLOCKED",
                "GTFS_TRANSFERS",
                "GTFS minimum is a lower bound on walking plus one buffer; scoped rules still require consumer validation."
        );
    }

    private TransferEdge sameAreaCandidate(String areaId) {
        if (areaId == null || !areaById.containsKey(areaId)) {
            return null;
        }
        AreaSpread spread = areaSpread(areaId);
        int seconds = spread.maxDistanceMeters() == null ? 0 : estimatedWalkingSeconds(spread.maxDistanceMeters());
        return new TransferEdge(
                edgeId(areaId, areaId),
                null,
                areaId,
                areaId,
                null,
                null,
                spread.maxDistanceMeters(),
                seconds,
                seconds == 0 ? 0 : (seconds + 59) / 60,
                false,
                "AREA_MEMBERSHIP_CANDIDATE",
                "AREA_MEMBERSHIP",
                "AREA",
                spread.complete() ? "MAX_CONCRETE_STOP_STRAIGHT_LINE" : "UNKNOWN",
                spread.complete() && spread.maxDistanceMeters() <= StopFootpathBuilder.MAX_ESTIMATED_TRAVERSABLE_METERS
                        ? "CANDIDATE"
                        : "UNKNOWN",
                "SAME_STOP_AREA",
                "Area membership is not a walking path; use concrete stop_footpaths and routing validation."
        );
    }

    private void addDistanceHeuristicEdges(Set<AreaPair> explicitPedestrianPairs, Consumer<TransferEdge> consumer) {
        Map<GridCell, List<StopArea>> grid = buildGrid();
        for (StopArea from : areaById.values()) {
            if (!hasCoordinates(from)) {
                continue;
            }
            List<NearbyCandidate> candidates = new ArrayList<>();
            GridCell cell = GridCell.from(from);
            for (int latOffset = -1; latOffset <= 1; latOffset++) {
                for (int lonOffset = -1; lonOffset <= 1; lonOffset++) {
                    List<StopArea> bucket = grid.get(new GridCell(cell.latBucket() + latOffset, cell.lonBucket() + lonOffset));
                    if (bucket == null) {
                        continue;
                    }
                    for (StopArea to : bucket) {
                        if (from.areaId().equals(to.areaId()) || !hasCoordinates(to)) {
                            continue;
                        }
                        int distance = distanceMeters(from.areaLat(), from.areaLon(), to.areaLat(), to.areaLon());
                        if (distance <= MAX_DISTANCE_HEURISTIC_METERS) {
                            candidates.add(new NearbyCandidate(to, distance));
                        }
                    }
                }
            }
            candidates.sort(Comparator
                    .comparingInt(NearbyCandidate::distanceMeters)
                    .thenComparing(candidate -> candidate.area().areaId()));
            int limit = Math.min(candidates.size(), MAX_NEARBY_CANDIDATES_PER_AREA);
            for (int index = 0; index < limit; index++) {
                NearbyCandidate candidate = candidates.get(index);
                AreaPair pair = new AreaPair(from.areaId(), candidate.area().areaId());
                if (explicitPedestrianPairs.contains(pair)) {
                    continue;
                }
                int seconds = estimatedWalkingSeconds(candidate.distanceMeters());
                consumer.accept(new TransferEdge(
                        edgeId(from.areaId(), candidate.area().areaId()),
                        null,
                        from.areaId(),
                        candidate.area().areaId(),
                        null,
                        null,
                        candidate.distanceMeters(),
                        seconds,
                        (seconds + 59) / 60,
                        false,
                        "NEARBY_AREA_CANDIDATE",
                        "DISTANCE_CANDIDATE",
                        "AREA",
                        "STRAIGHT_LINE_LOWER_BOUND",
                        heuristicQuality(candidate.distanceMeters()),
                        "DISTANCE_HEURISTIC",
                        "Nearby-area candidate only; straight-line distance does not prove a pedestrian path."
                ));
            }
        }
    }

    private AreaSpread areaSpread(String areaId) {
        List<Stop> members = boardingStopsByArea.getOrDefault(areaId, List.of());
        if (members.size() <= 1) {
            return new AreaSpread(null, false);
        }
        int maximum = 0;
        for (int left = 0; left < members.size(); left++) {
            for (int right = left + 1; right < members.size(); right++) {
                Integer distance = distanceBetweenStops(members.get(left).stopId(), members.get(right).stopId());
                if (distance == null) {
                    return new AreaSpread(null, false);
                }
                maximum = Math.max(maximum, distance);
            }
        }
        return new AreaSpread(maximum, true);
    }

    private Map<GridCell, List<StopArea>> buildGrid() {
        Map<GridCell, List<StopArea>> grid = new HashMap<>();
        for (StopArea area : areaById.values()) {
            if (hasCoordinates(area)) {
                grid.computeIfAbsent(GridCell.from(area), ignored -> new ArrayList<>()).add(area);
            }
        }
        return grid;
    }

    private Integer distanceBetweenStops(String fromStopId, String toStopId) {
        Stop from = stopById.get(fromStopId);
        Stop to = stopById.get(toStopId);
        if (from == null || to == null || !hasCoordinates(from) || !hasCoordinates(to)) {
            return null;
        }
        return distanceMeters(from.stopLat(), from.stopLon(), to.stopLat(), to.stopLon());
    }

    private static int estimatedWalkingSeconds(int distanceMeters) {
        return WalkTimeModel.minimumTransferSeconds(WalkTimeModel.estimatedWalkSeconds(distanceMeters), null);
    }

    private static String qualityForSeconds(int seconds) {
        if (seconds <= 240) return "GOOD";
        if (seconds <= 360) return "OK";
        if (seconds <= 600) return "LONG";
        return "AVOID";
    }

    private static String heuristicQuality(int distanceMeters) {
        if (distanceMeters <= 200) return "CANDIDATE";
        if (distanceMeters <= 400) return "LOW";
        return "UNKNOWN";
    }

    private static boolean hasCoordinates(StopArea area) {
        return area.areaLat() != null && area.areaLon() != null;
    }

    private static boolean hasCoordinates(Stop stop) {
        return stop.stopLat() != null
                && stop.stopLon() != null
                && stop.stopLat() >= -90.0 && stop.stopLat() <= 90.0
                && stop.stopLon() >= -180.0 && stop.stopLon() <= 180.0;
    }

    private static int distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double latRadians1 = Math.toRadians(lat1);
        double latRadians2 = Math.toRadians(lat2);
        double deltaLat = Math.toRadians(lat2 - lat1);
        double deltaLon = Math.toRadians(lon2 - lon1);
        double sinLat = Math.sin(deltaLat / 2.0);
        double sinLon = Math.sin(deltaLon / 2.0);
        double haversine = sinLat * sinLat
                + Math.cos(latRadians1) * Math.cos(latRadians2) * sinLon * sinLon;
        double centralAngle = 2.0 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1.0 - haversine));
        return (int) Math.round(EARTH_RADIUS_METERS * centralAngle);
    }

    private static String edgeId(String fromAreaId, String toAreaId) {
        return "edge_" + sanitize(fromAreaId) + "_" + sanitize(toAreaId);
    }

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9]+", "_");
    }

    public record TransferEdgeBuildResult(List<TransferEdge> edges, TransferEdgeStats stats) {
    }

    private static final class TransferEdgeStatsAccumulator {
        private final Map<String, Integer> sourceCounts = new LinkedHashMap<>();
        private final Map<String, Integer> qualityCounts = new LinkedHashMap<>();
        private int edgeCount;
        private int traversableCount;
        private int candidateOnlyCount;
        private Integer minDistance;
        private Integer maxDistance;
        private long distanceSum;
        private int distanceCount;
        private int minMinutes = Integer.MAX_VALUE;
        private int maxMinutes;
        private long minuteSum;

        private void accept(TransferEdge edge) {
            edgeCount++;
            if (edge.traversable()) traversableCount++; else candidateOnlyCount++;
            sourceCounts.merge(edge.source(), 1, Integer::sum);
            qualityCounts.merge(edge.quality(), 1, Integer::sum);
            if (edge.distanceMeters() != null) {
                minDistance = minDistance == null ? edge.distanceMeters() : Math.min(minDistance, edge.distanceMeters());
                maxDistance = maxDistance == null ? edge.distanceMeters() : Math.max(maxDistance, edge.distanceMeters());
                distanceSum += edge.distanceMeters();
                distanceCount++;
            }
            minMinutes = Math.min(minMinutes, edge.minTransferMinutes());
            maxMinutes = Math.max(maxMinutes, edge.minTransferMinutes());
            minuteSum += edge.minTransferMinutes();
        }

        private TransferEdgeStats toStats() {
            return new TransferEdgeStats(
                    edgeCount,
                    Map.copyOf(sourceCounts),
                    Map.copyOf(qualityCounts),
                    minDistance,
                    maxDistance,
                    distanceCount == 0 ? 0.0 : (double) distanceSum / distanceCount,
                    edgeCount == 0 ? 0 : minMinutes,
                    maxMinutes,
                    edgeCount == 0 ? 0.0 : (double) minuteSum / edgeCount,
                    traversableCount,
                    candidateOnlyCount
            );
        }
    }

    public record TransferEdgeStats(
            int edgeCount,
            Map<String, Integer> sourceCounts,
            Map<String, Integer> qualityCounts,
            Integer minDistanceMeters,
            Integer maxDistanceMeters,
            double averageDistanceMeters,
            int minTransferMinutes,
            int maxTransferMinutes,
            double averageTransferMinutes,
            int traversableCount,
            int candidateOnlyCount
    ) {
        public static TransferEdgeStats empty() {
            return new TransferEdgeStats(0, Map.of(), Map.of(), null, null, 0.0, 0, 0, 0.0, 0, 0);
        }
    }

    private record GridCell(int latBucket, int lonBucket) {
        private static GridCell from(StopArea area) {
            return new GridCell(
                    (int) Math.floor(area.areaLat() / GRID_SIZE_DEGREES),
                    (int) Math.floor(area.areaLon() / GRID_SIZE_DEGREES)
            );
        }
    }

    private record NearbyCandidate(StopArea area, int distanceMeters) {
    }

    private record AreaPair(String fromAreaId, String toAreaId) {
    }

    private record AreaSpread(Integer maxDistanceMeters, boolean complete) {
    }
}
