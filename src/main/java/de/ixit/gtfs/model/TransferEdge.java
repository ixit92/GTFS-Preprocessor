package de.ixit.gtfs.model;

public record TransferEdge(
        String transferEdgeId,
        Long rawTransferId,
        String fromStopAreaId,
        String toStopAreaId,
        String fromStopId,
        String toStopId,
        Integer distanceMeters,
        int minTransferSeconds,
        int minTransferMinutes,
        boolean traversable,
        String edgeKind,
        String transferSemantic,
        String scopeType,
        String distanceModel,
        String quality,
        String source,
        String explanation
) {
}
