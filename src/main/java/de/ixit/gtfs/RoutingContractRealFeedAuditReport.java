package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record RoutingContractRealFeedAuditReport(
        String auditVersion,
        String generatedAt,
        String database,
        String databaseSha256,
        long sqliteFileSizeBytes,
        String inputProvenance,
        Map<String, String> sourceFeeds,
        String scenarioVersion,
        List<String> requiredCities,
        List<ScenarioResult> scenarios,
        ServiceDayExceptionAudit serviceDayExceptions,
        OverflowTimeAudit overflowTimes,
        LatencySummary latency,
        List<String> failures,
        boolean pass
) {
    public record ScenarioResult(
            String id,
            String city,
            long elapsedMs,
            DisplayNameEvidence startDisplayName,
            DisplayNameEvidence targetDisplayName,
            RoutingContractConsumerReport consumer,
            List<String> failures,
            boolean pass
    ) {
    }

    public record DisplayNameEvidence(
            String areaId,
            String publicStopName,
            String publicCityName,
            String publicDisplayName,
            String displayQuality,
            boolean expectedCityMatches,
            boolean stationCityPatternMatches,
            boolean pass
    ) {
    }

    public record ServiceDayExceptionAudit(
            long additionRowCount,
            long removalRowCount,
            List<ServiceDayExceptionEvidence> samples,
            boolean additionsObserved,
            boolean removalsObserved,
            boolean pass
    ) {
    }

    public record ServiceDayExceptionEvidence(
            String serviceId,
            String serviceDate,
            int exceptionType,
            boolean expectedActive,
            boolean resolvedActive,
            String resolutionReason,
            long tripCount,
            boolean pass
    ) {
    }

    public record OverflowTimeAudit(
            long overflowRowCount,
            int maximumServiceDaySeconds,
            String maximumServiceDayTime,
            List<OverflowTimeEvidence> samples,
            boolean pass
    ) {
    }

    public record OverflowTimeEvidence(
            String tripId,
            String routeId,
            String serviceId,
            String stopId,
            int stopSequence,
            int arrivalSeconds,
            int departureSeconds,
            String arrivalTime,
            String departureTime,
            boolean tripReferenceValid,
            boolean pass
    ) {
    }

    public record LatencySummary(
            long maximumAllowedMs,
            long minimumMs,
            long averageMs,
            long p95Ms,
            long maximumMs,
            int measuredScenarioCount,
            boolean pass
    ) {
    }
}
