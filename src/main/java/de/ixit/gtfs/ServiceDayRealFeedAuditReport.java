package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record ServiceDayRealFeedAuditReport(
        String auditVersion,
        String generatedAt,
        String database,
        String databaseSha256,
        long sqliteFileSizeBytes,
        String inputProvenance,
        Map<String, String> sourceFeeds,
        Map<String, String> metadata,
        ServiceDayModelReport serviceDayModel,
        ExceptionStatistics exceptionStatistics,
        Map<String, Long> rowCounts,
        BaselineComparison baselineComparison,
        List<ServiceDateSpotcheck> serviceDateSpotchecks,
        Map<String, Long> performanceEvidence,
        List<String> failures,
        boolean pass
) {
    public record ExceptionStatistics(
            long additionRows,
            long removalRows,
            long servicesWithAdditions,
            long servicesWithRemovals,
            long exceptionOnlyServices
    ) {
    }

    public record BaselineComparison(
            String database,
            String databaseSha256,
            long sqliteFileSizeBytes,
            long sqliteFileSizeDeltaBytes,
            Map<String, String> metadata,
            Map<String, Long> rowCounts,
            Map<String, Long> rowCountDeltas
    ) {
    }

    public record ServiceDateSpotcheck(
            String serviceDate,
            String dayOfWeek,
            long activeServiceCount,
            long activeTripCount,
            long baseCalendarActiveServiceCount,
            long additionsApplied,
            long removalsApplied
    ) {
    }
}
