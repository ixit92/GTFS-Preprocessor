package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StopAreaReporter {
    public static final int VERY_LARGE_STOP_AREA_THRESHOLD = 50;

    private StopAreaReporter() {
    }

    public static StopAreaStats summarize(List<Stop> stops, List<StopArea> stopAreas) {
        Map<String, Integer> membersByArea = new HashMap<>();
        Set<String> parentBackedAreaIds = new HashSet<>();

        for (Stop stop : stops) {
            String areaId = StopAreaBuilder.areaIdFor(stop);
            membersByArea.merge(areaId, 1, Integer::sum);
            if (stop.parentStation() != null && !stop.parentStation().isBlank()) {
                parentBackedAreaIds.add(areaId);
            }
        }

        int singleStopAreas = 0;
        int areasWithoutParentStation = 0;
        int areasWithoutMembers = 0;
        List<StopAreaSummary> largest = new ArrayList<>();
        List<StopAreaSummary> veryLarge = new ArrayList<>();

        for (StopArea area : stopAreas) {
            int memberCount = membersByArea.getOrDefault(area.areaId(), 0);
            if (memberCount == 1) {
                singleStopAreas++;
            }
            if (!parentBackedAreaIds.contains(area.areaId())) {
                areasWithoutParentStation++;
            }
            if (memberCount == 0) {
                areasWithoutMembers++;
            }

            StopAreaSummary summary = new StopAreaSummary(area.areaId(), area.areaName(), memberCount);
            largest.add(summary);
            if (memberCount >= VERY_LARGE_STOP_AREA_THRESHOLD) {
                veryLarge.add(summary);
            }
        }

        largest.sort(Comparator.comparingInt(StopAreaSummary::stopCount).reversed().thenComparing(StopAreaSummary::areaId));
        veryLarge.sort(Comparator.comparingInt(StopAreaSummary::stopCount).reversed().thenComparing(StopAreaSummary::areaId));
        return new StopAreaStats(
                stopAreas.size(),
                singleStopAreas,
                areasWithoutParentStation,
                areasWithoutMembers,
                List.copyOf(largest.stream().limit(5).toList()),
                List.copyOf(veryLarge)
        );
    }

    public record StopAreaStats(
            int stopAreaCount,
            int singleStopAreas,
            int areasWithoutParentStation,
            int areasWithoutMembers,
            List<StopAreaSummary> largestStopAreas,
            List<StopAreaSummary> veryLargeStopAreas
    ) {
    }

    public record StopAreaSummary(String areaId, String areaName, int stopCount) {
        public String toReportText() {
            String displayName = areaName == null || areaName.isBlank() ? "(unnamed)" : areaName;
            return areaId + " [" + stopCount + "] " + displayName;
        }
    }
}
