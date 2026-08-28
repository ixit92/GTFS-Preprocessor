package de.ixit.gtfs.model;

public record CanonicalStopAreaTransferEdge(
        String canonicalAreaId,
        String fromAreaId,
        String toAreaId,
        Integer distanceMeters,
        int minTransferMinutes,
        String quality,
        String source,
        String explanation
) {
}
