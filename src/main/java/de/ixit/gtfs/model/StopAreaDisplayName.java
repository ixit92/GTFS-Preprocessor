package de.ixit.gtfs.model;

public record StopAreaDisplayName(
        String areaId,
        String canonicalAreaId,
        String publicDisplayName,
        String publicDisplayNameNormalized,
        String publicStopName,
        String publicCityName,
        String displayQuality,
        String source,
        String explanation
) {
}
