package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record DisplayNameQualityBaselineReport(
        String baselineVersion,
        boolean available,
        long findingCount,
        long prefixFindingCount,
        long municipalityOnlyFindingCount,
        long coverageGapCount,
        long destructiveActionCount,
        Map<String, Long> classificationCounts,
        List<String> samples
) {
    public boolean pass() {
        return available && coverageGapCount == 0 && destructiveActionCount == 0;
    }
}
