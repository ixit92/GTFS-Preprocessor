package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record DisplayNameAuditReport(
        String auditVersion,
        boolean available,
        long scannedNames,
        long formatMismatches,
        long municipalityOnlyNames,
        long duplicateCityNamePrefixes,
        long matchingCityCodePrefixes,
        long matchingCityQualifiers,
        long suspiciousUnknownPrefixes,
        long transformedNames,
        long invalidTransformationRuleRows,
        Map<String, Long> transformationRuleCounts,
        List<String> samples
) {
    public boolean pass() {
        return available
                && formatMismatches == 0
                && duplicateCityNamePrefixes == 0
                && matchingCityCodePrefixes == 0
                && matchingCityQualifiers == 0
                && invalidTransformationRuleRows == 0;
    }

    public long residualCount() {
        return formatMismatches
                + duplicateCityNamePrefixes
                + matchingCityCodePrefixes
                + matchingCityQualifiers
                + invalidTransformationRuleRows;
    }
}
