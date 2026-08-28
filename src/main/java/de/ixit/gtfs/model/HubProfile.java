package de.ixit.gtfs.model;

public record HubProfile(
        String areaId,
        String hubLevel,
        int stopCount,
        int routeCount,
        int tripCount,
        int routeTypeCount,
        int stopTimeCount,
        boolean hasTrain,
        boolean hasSubway,
        boolean hasTram,
        boolean hasBus,
        boolean hasRailKeyword,
        boolean hasMainStationKeyword,
        int transferCandidateScore,
        String explanation
) {
}
