package de.ixit.gtfs.model;

public record RouteAxisStop(
        String axisId,
        int sequenceIndex,
        String areaId
) {
}
