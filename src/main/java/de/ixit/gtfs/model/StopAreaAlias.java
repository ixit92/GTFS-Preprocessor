package de.ixit.gtfs.model;

public record StopAreaAlias(
        String areaId,
        String alias,
        String aliasNormalized,
        String aliasType,
        String source,
        int priority
) {
}
