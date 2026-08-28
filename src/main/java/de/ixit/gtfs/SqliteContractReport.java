package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record SqliteContractReport(
        String schemaVersion,
        String preprocessorVersion,
        String contractName,
        String contractVersion,
        Map<String, String> metadata,
        List<String> tables,
        List<String> indexes,
        Map<String, Long> rowCounts,
        HubProfileBuilder.HubProfileStats hubProfileStats,
        RouteAxisBuilder.RouteAxisStats routeAxisStats,
        TransferRuleBuilder.TransferRuleStats transferRuleStats,
        TransferEdgeBuilder.TransferEdgeStats transferEdgeStats,
        String timeModel,
        String stopIdPolicy,
        String areaIdPolicy,
        String searchTokensPolicy
) {
}
