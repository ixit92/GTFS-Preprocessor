package de.ixit.gtfs.model;

public record TransferRule(
        String transferRuleId,
        Long rawTransferId,
        String fromAreaId,
        String toAreaId,
        String fromStopId,
        String toStopId,
        Integer transferType,
        Integer minTransferTimeSeconds,
        String transferSemantic,
        String scopeType,
        boolean pedestrianUsable,
        String source,
        String confidence,
        String explanation
) {
}
