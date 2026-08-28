package de.ixit.gtfs.model;

public record StopSearchToken(
        String stopId,
        String areaId,
        String token,
        String tokenType,
        String source
) {
}
