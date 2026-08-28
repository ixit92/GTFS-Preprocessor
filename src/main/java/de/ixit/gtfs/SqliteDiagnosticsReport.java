package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record SqliteDiagnosticsReport(
        String runMode,
        boolean derivedBuildersSkipped,
        List<String> skippedDerivedBuilders,
        int batchSize,
        int stopTimesCommitRows,
        long stopTimesRows,
        long stopTimesDurationMs,
        long stopTimesRowsPerSecond,
        long stopTimesCommitCount,
        long stopTimesAverageCommitMs,
        long stopTimesMaxCommitMs,
        long sqliteSizeAfterStopTimesBytes,
        long walSizeAfterStopTimesBytes,
        Map<String, String> sqlitePragmas
) {
    public static SqliteDiagnosticsReport from(PreprocessOptions options, SqliteGtfsWriter.StopTimesWriteReport stopTimesReport) {
        return new SqliteDiagnosticsReport(
                options.runMode(),
                options.skipDerivedBuilders(),
                List.copyOf(options.skippedDerivedBuilders()),
                stopTimesReport.batchSize(),
                stopTimesReport.stopTimesCommitRows(),
                stopTimesReport.rows(),
                stopTimesReport.durationMs(),
                stopTimesReport.rowsPerSecond(),
                stopTimesReport.commitCount(),
                stopTimesReport.averageCommitMs(),
                stopTimesReport.maxCommitMs(),
                stopTimesReport.sqliteSizeAfterBytes(),
                stopTimesReport.walSizeAfterBytes(),
                Map.copyOf(stopTimesReport.sqlitePragmas())
        );
    }
}
