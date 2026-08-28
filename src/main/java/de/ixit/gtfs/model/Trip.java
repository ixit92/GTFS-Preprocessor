package de.ixit.gtfs.model;

public record Trip(
        String tripId,
        String routeId,
        String serviceId,
        String tripHeadsign,
        String directionId,
        String blockId,
        String shapeId
) {
}
