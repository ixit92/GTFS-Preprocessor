package de.ixit.gtfs.model;

public record StopAreaProfile(
        String areaId,
        String profileClass,
        int stopCount,
        int platformCount,
        int routeCount,
        int tripCount,
        int stopTimeCount,
        String routeTypes,
        String lineLabels,
        boolean hasRailService,
        boolean hasTrain,
        boolean hasSubway,
        boolean hasTram,
        boolean hasBus,
        boolean busOnly,
        boolean stationNameSignal,
        boolean mainStationSignal,
        int searchPriorityScore,
        String explanation
) {
}
