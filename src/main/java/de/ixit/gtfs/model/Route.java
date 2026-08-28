package de.ixit.gtfs.model;

public record Route(
        String routeId,
        String agencyId,
        String routeShortName,
        String routeLongName,
        Integer routeType,
        String routeColor,
        String routeTextColor
) {
}
