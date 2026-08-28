package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record AppReadySqliteReport(
        boolean appReady,
        Map<String, Boolean> featureFlags,
        Map<String, Long> qualityChecks,
        DisplayNameAuditReport displayNameAudit,
        DisplayNameQualityBaselineReport displayNameQualityBaseline,
        ServiceDayModelReport serviceDayModel,
        List<String> warnings
) {
}
