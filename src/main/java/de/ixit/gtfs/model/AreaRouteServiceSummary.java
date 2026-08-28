package de.ixit.gtfs.model;

public record AreaRouteServiceSummary(
        String areaId,
        String routeId,
        Integer routeType,
        String lineLabel,
        int stopTimeCount,
        int tripCount
) {
}
