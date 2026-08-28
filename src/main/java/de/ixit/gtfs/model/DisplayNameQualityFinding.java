package de.ixit.gtfs.model;

public record DisplayNameQualityFinding(
        String areaId,
        String findingType,
        String classification,
        String prefix,
        String publicStopName,
        String publicCityName,
        String publicDisplayName,
        String action,
        String rationale
) {
}
