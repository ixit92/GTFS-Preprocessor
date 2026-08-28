package de.ixit.gtfs.model;

public record StopAreaCity(
        String areaId,
        String municipalityId,
        String cityName,
        String municipalityType,
        String source,
        String quality,
        String dataVersion,
        String explanation
) {
}
