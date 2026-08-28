package de.ixit.gtfs.model;

public record StopArea(
        String areaId,
        String areaName,
        Double areaLat,
        Double areaLon,
        int stopCount
) {
}
