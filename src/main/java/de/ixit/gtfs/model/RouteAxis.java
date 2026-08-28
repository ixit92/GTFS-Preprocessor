package de.ixit.gtfs.model;

public record RouteAxis(
        String axisId,
        String routeId,
        String directionId,
        String representativeTripId,
        int tripCount,
        int stopCount,
        String firstAreaId,
        String lastAreaId,
        String routeShortName,
        String routeLongName,
        Integer routeType,
        String explanation
) {
}
