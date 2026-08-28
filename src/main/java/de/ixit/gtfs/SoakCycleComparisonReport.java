package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record SoakCycleComparisonReport(
        String validationVersion,
        String generatedAt,
        String cycleId,
        String comparisonPolicy,
        String baselineContractReport,
        String candidateContractReport,
        String candidateAuditReport,
        Map<String, String> sourceFeedHashes,
        Map<String, Long> rowCountDeltas,
        Map<String, Long> serviceDayDeltas,
        Map<String, Long> displayQualityDeltas,
        int maxHeapLimitMb,
        long maximumReportedUsedHeapMb,
        boolean candidateAppReady,
        int routingCompatibilityWarnCount,
        boolean candidateAuditPass,
        List<Check> checks,
        List<String> failures,
        boolean pass
) {
    public record Check(String id, String status, String expected, String actual) {
    }
}
