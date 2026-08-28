package de.ixit.gtfs.model;

public record CanonicalStopArea(
        String canonicalAreaId,
        String canonicalDisplayName,
        String originalName,
        String cityName,
        String stationName,
        String nameOrder,
        String primaryStopAreaId,
        String profileClass,
        boolean hasRailService,
        String lineLabels,
        int memberCount,
        String displayQuality,
        String source,
        String explanation
) {
}
