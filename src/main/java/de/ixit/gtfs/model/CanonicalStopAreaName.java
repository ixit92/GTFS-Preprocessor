package de.ixit.gtfs.model;

public record CanonicalStopAreaName(
        String canonicalAreaId,
        String originalName,
        String displayName,
        String displayNameNormalized,
        String cityName,
        String stationName,
        String nameOrder,
        String displayQuality,
        String source,
        String explanation
) {
}
