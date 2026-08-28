package de.ixit.gtfs.model;

public record Stop(
        String stopId,
        String stopCode,
        String stopName,
        Double stopLat,
        Double stopLon,
        String parentStation,
        Integer locationType,
        String platformCode
) {
}
