package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record RealFeedDriftReport(
        String auditVersion,
        String generatedAt,
        String baselineContractReport,
        String candidateContractReport,
        String candidateAuditReport,
        String candidateAuditCompatibility,
        int maxHeapLimitMb,
        long maximumReportedUsedHeapMb,
        Map<String, SourceRevision> sourceRevisions,
        List<MetricDrift> metrics,
        List<String> failures,
        String baselinePromotionPolicy,
        String baselinePromotionState,
        boolean pass
) {
    public record SourceRevision(String baselineSha256, String candidateSha256, boolean changed) {
    }

    public record MetricDrift(
            String domain,
            String metric,
            long baseline,
            long candidate,
            long delta,
            double absoluteChangePercent,
            double reviewThresholdPercent,
            String classification
    ) {
    }
}
