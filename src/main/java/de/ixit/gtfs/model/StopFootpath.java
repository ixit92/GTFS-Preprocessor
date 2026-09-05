package de.ixit.gtfs.model;

public record StopFootpath(
        String footpathId,
        String areaId,
        String fromStopId,
        String toStopId,
        Integer distanceMeters,
        Integer minTransferSeconds,
        boolean traversable,
        String quality,
        String distanceModel,
        String timeModel,
        String source,
        String explanation,
        Integer walkSeconds,
        Integer transferBufferSeconds,
        Integer gtfsMinTransferSeconds,
        java.util.List<String> pathwayIds,
        int pathwayModes
) {
}
