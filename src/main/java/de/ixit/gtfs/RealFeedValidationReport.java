package de.ixit.gtfs;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

public record RealFeedValidationReport(
        String validationVersion,
        Path inputFeed,
        Instant generatedAt,
        long sqliteFileSizeBytes,
        PerformanceReport performance,
        Map<String, Long> tableRowCounts,
        Map<String, Integer> warningCounts,
        int totalWarnings,
        int criticalWarnings,
        Map<String, String> indexSmokeChecks,
        String contractVersion,
        String preprocessorVersion,
        String routingCompatibilityAuditStatus,
        SqliteDiagnosticsReport sqliteDiagnostics
) {
}
