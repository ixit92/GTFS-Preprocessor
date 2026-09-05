package de.ixit.gtfs.model;

public record Pathway(
        String pathwayId,
        String fromStopId,
        String toStopId,
        Integer mode,
        Integer bidirectional,
        Double lengthMeters,
        Integer traversalSeconds,
        Integer stairCount
) {
}
