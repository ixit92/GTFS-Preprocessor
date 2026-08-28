package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StopAreaBuilder {
    private StopAreaBuilder() {
    }

    public static String areaIdFor(Stop stop) {
        String parentStation = stop.parentStation();
        return parentStation == null || parentStation.isBlank() ? stop.stopId() : parentStation;
    }

    public static List<StopArea> fromStops(List<Stop> stops) {
        Map<String, AreaAccumulator> accumulators = new LinkedHashMap<>();
        Map<String, Stop> stopsById = new LinkedHashMap<>();
        for (Stop stop : stops) {
            stopsById.put(stop.stopId(), stop);
        }

        for (Stop stop : stops) {
            String areaId = areaIdFor(stop);
            AreaAccumulator accumulator = accumulators.computeIfAbsent(areaId, ignored -> new AreaAccumulator(areaId));
            accumulator.add(stop);
        }

        List<StopArea> areas = new ArrayList<>();
        for (AreaAccumulator accumulator : accumulators.values()) {
            Stop parent = stopsById.get(accumulator.areaId);
            String name = parent != null && parent.stopName() != null ? parent.stopName() : accumulator.firstName;
            areas.add(new StopArea(accumulator.areaId, name, accumulator.meanLat(), accumulator.meanLon(), accumulator.stopCount));
        }
        return areas;
    }

    private static final class AreaAccumulator {
        private final String areaId;
        private String firstName;
        private double latSum;
        private double lonSum;
        private int coordinateCount;
        private int stopCount;

        private AreaAccumulator(String areaId) {
            this.areaId = areaId;
        }

        private void add(Stop stop) {
            if (firstName == null) {
                firstName = stop.stopName();
            }
            if (stop.stopLat() != null && stop.stopLon() != null) {
                latSum += stop.stopLat();
                lonSum += stop.stopLon();
                coordinateCount++;
            }
            stopCount++;
        }

        private Double meanLat() {
            return coordinateCount == 0 ? null : latSum / coordinateCount;
        }

        private Double meanLon() {
            return coordinateCount == 0 ? null : lonSum / coordinateCount;
        }
    }
}
