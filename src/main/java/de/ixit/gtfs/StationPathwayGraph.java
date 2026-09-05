package de.ixit.gtfs;

import de.ixit.gtfs.model.Pathway;
import de.ixit.gtfs.model.Stop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Directed station graph; keeps only one source's shortest-path tree in memory. */
final class StationPathwayGraph {
    private final Map<String, List<Arc>> outgoing = new HashMap<>();
    private final Map<String, List<String>> boardingAreas = new HashMap<>();
    private final Set<String> coveredAreas = new HashSet<>();
    private int unusableRows;

    StationPathwayGraph(List<Stop> stops, List<Pathway> pathways) {
        if (pathways.isEmpty()) return;
        Map<String, Stop> byId = new HashMap<>();
        for (Stop stop : stops) {
            byId.put(stop.stopId(), stop);
            if (Integer.valueOf(4).equals(stop.locationType())) {
                boardingAreas.computeIfAbsent(stop.parentStation(), ignored -> new ArrayList<>()).add(stop.stopId());
            }
        }
        for (Pathway path : pathways) {
            Stop from = byId.get(path.fromStopId());
            Stop to = byId.get(path.toStopId());
            String fromArea = stationArea(from, byId);
            String toArea = stationArea(to, byId);
            // Even incomplete or invalid supplied graphs suppress geometry shortcuts in that station.
            if (fromArea != null) coveredAreas.add(fromArea);
            if (toArea != null) coveredAreas.add(toArea);
            Integer seconds = traversalSeconds(path);
            if (!isNode(from) || !isNode(to) || fromArea == null || !fromArea.equals(toArea)
                    || path.bidirectional() == null || path.bidirectional() < 0 || path.bidirectional() > 1
                    || Integer.valueOf(7).equals(path.mode()) && path.bidirectional() == 1
                    || seconds == null) {
                unusableRows++;
                continue;
            }
            boolean estimated = path.traversalSeconds() == null;
            add(path.fromStopId(), path.toStopId(), path, seconds, estimated);
            if (path.bidirectional() == 1) add(path.toStopId(), path.fromStopId(), path, seconds, estimated);
        }
        for (List<Arc> arcs : outgoing.values()) {
            arcs.sort(Comparator.comparing(Arc::to).thenComparing(arc -> arc.path.pathwayId()));
        }
    }

    boolean covers(String areaId) {
        return coveredAreas.contains(areaId);
    }

    int unusableRows() {
        return unusableRows;
    }

    void clear() {
        outgoing.clear();
        boardingAreas.clear();
        coveredAreas.clear();
    }

    Paths pathsFrom(String fromStop) {
        Map<String, Integer> distances = new HashMap<>();
        Map<String, Arc> previous = new HashMap<>();
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingInt(Visit::seconds).thenComparing(Visit::stop));
        for (String start : endpoints(fromStop)) {
            distances.put(start, 0);
            queue.add(new Visit(start, 0));
        }
        while (!queue.isEmpty()) {
            Visit visit = queue.remove();
            if (visit.seconds != distances.get(visit.stop)) continue;
            for (Arc arc : outgoing.getOrDefault(visit.stop, List.of())) {
                long next = (long) visit.seconds + arc.seconds;
                if (next >= Integer.MAX_VALUE || next >= distances.getOrDefault(arc.to, Integer.MAX_VALUE)) continue;
                distances.put(arc.to, (int) next);
                previous.put(arc.to, arc);
                queue.add(new Visit(arc.to, (int) next));
            }
        }
        return new Paths(distances, previous);
    }

    private List<String> endpoints(String stopId) {
        return boardingAreas.getOrDefault(stopId, List.of(stopId));
    }

    final class Paths {
        private final Map<String, Integer> distances;
        private final Map<String, Arc> previous;

        private Paths(Map<String, Integer> distances, Map<String, Arc> previous) {
            this.distances = distances;
            this.previous = previous;
        }

        Evidence to(String stopId) {
            String target = endpoints(stopId).stream().filter(distances::containsKey)
                    .min(Comparator.comparingInt((String id) -> distances.get(id)).thenComparing(id -> id)).orElse(null);
            if (target == null) return null;
            List<String> ids = new ArrayList<>();
            boolean estimated = false;
            boolean knownLength = true;
            double length = 0;
            int modes = 0;
            Arc arc = previous.get(target);
            while (arc != null) {
                ids.add(arc.path.pathwayId());
                modes |= 1 << (arc.path.mode() - 1);
                estimated |= arc.estimated;
                if (arc.path.lengthMeters() == null) knownLength = false;
                else length += arc.path.lengthMeters();
                arc = previous.get(arc.from);
            }
            if (ids.isEmpty()) return null;
            Collections.reverse(ids);
            return new Evidence(distances.get(target), knownLength ? WalkTimeModel.boundedSeconds(Math.ceil(length)) : null,
                    List.copyOf(ids), modes, estimated);
        }
    }

    private void add(String from, String to, Pathway path, int seconds, boolean estimated) {
        outgoing.computeIfAbsent(from, ignored -> new ArrayList<>()).add(new Arc(from, to, path, seconds, estimated));
    }

    private static boolean isNode(Stop stop) {
        return stop != null && (stop.locationType() == null || stop.locationType() == 0
                || stop.locationType() >= 2 && stop.locationType() <= 4);
    }

    private static String stationArea(Stop stop, Map<String, Stop> byId) {
        if (stop == null) return null;
        if (Integer.valueOf(4).equals(stop.locationType())) {
            stop = byId.get(stop.parentStation());
            if (stop == null) return null;
        }
        return StopAreaBuilder.areaIdFor(stop);
    }

    static Integer traversalSeconds(Pathway path) {
        if (path.mode() == null || path.mode() < 1 || path.mode() > 7) return null;
        Double length = path.lengthMeters();
        if (length != null && (!Double.isFinite(length) || length < 0 || length > Integer.MAX_VALUE)) return null;
        if (path.traversalSeconds() != null) return path.traversalSeconds() > 0 ? path.traversalSeconds() : null;
        // Mechanical modes require a supplied time: length cannot predict elevator/escalator waiting.
        if (path.mode() == 2 && path.stairCount() != null && path.stairCount() != 0) {
            return WalkTimeModel.boundedSeconds(Math.ceil(Math.abs((double) path.stairCount()) / 0.75
                    + (length == null ? 0 : length / WalkTimeModel.SPEED_METERS_PER_SECOND)));
        }
        if (length == null || path.mode() >= 2 && path.mode() <= 5) return null;
        return Math.max(1, WalkTimeModel.boundedSeconds(Math.ceil(length / WalkTimeModel.SPEED_METERS_PER_SECOND)
                + (path.mode() >= 6 ? 10 : 0)));
    }

    record Evidence(int walkSeconds, Integer lengthMeters, List<String> pathwayIds, int modes, boolean estimated) {
    }

    private record Arc(String from, String to, Pathway path, int seconds, boolean estimated) {
    }

    private record Visit(String stop, int seconds) {
    }
}
