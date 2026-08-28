package de.ixit.gtfs.model;

public record CanonicalStopAreaMember(
        String canonicalAreaId,
        String areaId,
        String memberRole,
        String displayRole,
        boolean primaryForSearch,
        boolean primaryForRouting,
        boolean visibleSuggestion,
        int accessCostMinutes,
        String quality,
        Integer distanceMeters,
        String source,
        String explanation
) {
}
