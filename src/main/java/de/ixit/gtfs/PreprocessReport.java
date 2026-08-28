package de.ixit.gtfs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record PreprocessReport(
        Path inputZip,
        Path outputDatabase,
        long stops,
        long routes,
        long trips,
        long stopTimes,
        long transfers,
        long calendarRows,
        long calendarDateRows,
        long stopAreas,
        long searchTokens,
        StopAreaReporter.StopAreaStats stopAreaStats,
        HubProfileBuilder.HubProfileStats hubProfileStats,
        RouteAxisBuilder.RouteAxisStats routeAxisStats,
        TransferRuleBuilder.TransferRuleStats transferRuleStats,
        StopFootpathBuilder.StopFootpathStats stopFootpathStats,
        RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingCompatibilityAudit,
        TransferFootpathAuditReport transferFootpathAudit,
        SqliteContractReport contractReport,
        PerformanceReport performanceReport,
        WarningSummary warningSummary,
        Map<String, String> indexSmokeChecks,
        SqliteDiagnosticsReport sqliteDiagnostics,
        AppReadySqliteReport appReadySqliteReport,
        ServiceDayModelReport serviceDayModelReport,
        RealFeedValidationReport realFeedValidationReport,
        List<String> warnings
) {
    private static final int MAX_CONSOLE_LIST_ITEMS = 25;
    private static final int MAX_CONSOLE_INLINE_ITEMS = 50;

    public String toConsoleText() {
        StringBuilder text = new StringBuilder();
        text.append("IXIT GTFS PreprocessReport").append(System.lineSeparator());
        text.append("Input: ").append(inputZip.toAbsolutePath()).append(System.lineSeparator());
        text.append("Output: ").append(outputDatabase.toAbsolutePath()).append(System.lineSeparator());
        text.append("Stops: ").append(stops).append(System.lineSeparator());
        text.append("Routes: ").append(routes).append(System.lineSeparator());
        text.append("Trips: ").append(trips).append(System.lineSeparator());
        text.append("StopTimes: ").append(stopTimes).append(System.lineSeparator());
        text.append("Transfers: ").append(transfers).append(System.lineSeparator());
        text.append("CalendarRows: ").append(calendarRows).append(System.lineSeparator());
        text.append("CalendarDateRows: ").append(calendarDateRows).append(System.lineSeparator());
        text.append("StopAreas: ").append(stopAreas).append(System.lineSeparator());
        text.append("SearchTokens: ").append(searchTokens).append(System.lineSeparator());
        if (stopAreaStats != null) {
            text.append("StopArea single-stop areas: ").append(stopAreaStats.singleStopAreas()).append(System.lineSeparator());
            text.append("StopAreas without parent_station grouping: ").append(stopAreaStats.areasWithoutParentStation()).append(System.lineSeparator());
            text.append("StopAreas without members: ").append(stopAreaStats.areasWithoutMembers()).append(System.lineSeparator());
            if (!stopAreaStats.largestStopAreas().isEmpty()) {
                text.append("Largest StopAreas:").append(System.lineSeparator());
                for (StopAreaReporter.StopAreaSummary summary : stopAreaStats.largestStopAreas()) {
                    text.append("- ").append(summary.toReportText()).append(System.lineSeparator());
                }
            }
            if (!stopAreaStats.veryLargeStopAreas().isEmpty()) {
                text.append("Very large StopAreas: ").append(stopAreaStats.veryLargeStopAreas().size()).append(System.lineSeparator());
            }
        }
        if (hubProfileStats != null) {
            text.append("HubProfiles: ").append(hubProfileStats.profileCount()).append(System.lineSeparator());
            text.append("HubProfile levels: ").append(hubProfileStats.levelCounts()).append(System.lineSeparator());
            if (!hubProfileStats.topHubs().isEmpty()) {
                text.append("Top HubProfiles:").append(System.lineSeparator());
                for (var profile : hubProfileStats.topHubs()) {
                    text.append("- ")
                            .append(profile.areaId())
                            .append(" ")
                            .append(profile.hubLevel())
                            .append(" routes=")
                            .append(profile.routeCount())
                            .append(" trips=")
                            .append(profile.tripCount())
                            .append(" score=")
                            .append(profile.transferCandidateScore())
                            .append(System.lineSeparator());
                }
            }
            if (!hubProfileStats.mainStationCandidates().isEmpty()) {
                text.append("MAIN_STATION_CANDIDATE:").append(System.lineSeparator());
                List<de.ixit.gtfs.model.HubProfile> mainStationCandidates = hubProfileStats.mainStationCandidates();
                int shown = Math.min(MAX_CONSOLE_LIST_ITEMS, mainStationCandidates.size());
                for (var profile : mainStationCandidates.subList(0, shown)) {
                    text.append("- ")
                            .append(profile.areaId())
                            .append(" routes=")
                            .append(profile.routeCount())
                            .append(" trips=")
                            .append(profile.tripCount())
                            .append(" reason=")
                            .append(profile.explanation())
                            .append(System.lineSeparator());
                }
                appendOmittedCount(text, mainStationCandidates.size(), shown);
            }
        }
        if (routeAxisStats != null) {
            text.append("RouteAxes: ").append(routeAxisStats.axisCount()).append(System.lineSeparator());
            text.append("RouteAxisStops: ").append(routeAxisStats.axisStopCount()).append(System.lineSeparator());
            if (!routeAxisStats.topAxesByTripCount().isEmpty()) {
                text.append("Top RouteAxes:").append(System.lineSeparator());
                for (var axis : routeAxisStats.topAxesByTripCount()) {
                    text.append("- ")
                            .append(axis.axisId())
                            .append(" route=")
                            .append(axis.routeId())
                            .append(" direction=")
                            .append(axis.directionId())
                            .append(" trips=")
                            .append(axis.tripCount())
                            .append(" stops=")
                            .append(axis.stopCount())
                            .append(System.lineSeparator());
                }
            }
            List<java.util.Map.Entry<String, Integer>> routesWithMostAxes = routeAxisStats.routesWithMostAxes(10);
            if (!routesWithMostAxes.isEmpty()) {
                text.append("Routes with most axes:").append(System.lineSeparator());
                for (var entry : routesWithMostAxes) {
                    text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
                }
            }
            if (!routeAxisStats.routesWithoutUsableSequence().isEmpty()) {
                List<String> routesWithoutUsableSequence = routeAxisStats.routesWithoutUsableSequence();
                int shown = Math.min(MAX_CONSOLE_INLINE_ITEMS, routesWithoutUsableSequence.size());
                text.append("Routes without usable StopArea sequence: ")
                        .append(String.join(", ", routesWithoutUsableSequence.subList(0, shown)));
                if (routesWithoutUsableSequence.size() > shown) {
                    text.append(" ... +")
                            .append(routesWithoutUsableSequence.size() - shown)
                            .append(" more");
                }
                text
                        .append(System.lineSeparator());
            }
        }
        if (transferRuleStats != null) {
            text.append("TransferRules: ").append(transferRuleStats.ruleCount()).append(System.lineSeparator());
            text.append("TransferRule sources: ").append(transferRuleStats.sourceCounts()).append(System.lineSeparator());
            text.append("TransferRule confidence: ").append(transferRuleStats.confidenceCounts()).append(System.lineSeparator());
            text.append("GTFS transfers mapped: ")
                    .append(transferRuleStats.gtfsTransfersMapped())
                    .append("/")
                    .append(transferRuleStats.gtfsTransfersObserved())
                    .append(System.lineSeparator());
            text.append("GTFS transfers not mappable: ").append(transferRuleStats.gtfsTransfersUnmapped()).append(System.lineSeparator());
            text.append("SAME_STOP_AREA rules: ").append(transferRuleStats.sameStopAreaRules()).append(System.lineSeparator());
            text.append("GTFS transfer semantics: ").append(transferRuleStats.semanticCounts()).append(System.lineSeparator());
            text.append("Scoped GTFS transfers: ").append(transferRuleStats.scopedGtfsTransfers()).append(System.lineSeparator());
            text.append("Pedestrian GTFS candidates: ").append(transferRuleStats.pedestrianCandidateTransfers()).append(System.lineSeparator());
            text.append("Excluded non-pedestrian/scoped transfers: ").append(transferRuleStats.excludedNonPedestrianTransfers()).append(System.lineSeparator());
        }
        if (stopFootpathStats != null) {
            text.append("StopFootpaths: ").append(stopFootpathStats.footpathCount()).append(System.lineSeparator());
            text.append("StopFootpaths traversable estimates: ").append(stopFootpathStats.traversableCount()).append(System.lineSeparator());
            text.append("StopFootpaths unknown/blocked: ").append(stopFootpathStats.unknownCount()).append(System.lineSeparator());
            text.append("StopFootpath quality: ").append(stopFootpathStats.qualityCounts()).append(System.lineSeparator());
            text.append("Oversized same-area groups (>400m): ").append(stopFootpathStats.oversizedAreas()).append(System.lineSeparator());
            text.append("Extreme same-area groups (>700m): ").append(stopFootpathStats.extremeAreas()).append(System.lineSeparator());
        }
        if (contractReport != null) {
            text.append("SQLite Contract:").append(System.lineSeparator());
            text.append("- schema_version: ").append(contractReport.schemaVersion()).append(System.lineSeparator());
            text.append("- contract_version: ").append(contractReport.contractVersion()).append(System.lineSeparator());
            text.append("- tables: ").append(String.join(", ", contractReport.tables())).append(System.lineSeparator());
            text.append("- time_model: ").append(contractReport.timeModel()).append(System.lineSeparator());
            text.append("- stop_id_policy: ").append(contractReport.stopIdPolicy()).append(System.lineSeparator());
            text.append("- area_id_policy: ").append(contractReport.areaIdPolicy()).append(System.lineSeparator());
            text.append("- search_tokens_policy: ").append(contractReport.searchTokensPolicy()).append(System.lineSeparator());
        }
        if (routingCompatibilityAudit != null) {
            text.append("Routing Compatibility Audit:").append(System.lineSeparator());
            text.append("- audit_version: ").append(routingCompatibilityAudit.auditVersion()).append(System.lineSeparator());
            text.append("- pass: ").append(routingCompatibilityAudit.passCount()).append(System.lineSeparator());
            text.append("- warn: ").append(routingCompatibilityAudit.warnCount()).append(System.lineSeparator());
            text.append("- info: ").append(routingCompatibilityAudit.infoCount()).append(System.lineSeparator());
            for (var item : routingCompatibilityAudit.items()) {
                text.append("- ")
                        .append(item.status())
                        .append(" ")
                        .append(item.id())
                        .append(": ")
                        .append(item.summary())
                        .append(System.lineSeparator());
            }
        }
        if (transferFootpathAudit != null) {
            text.append("Transfer & Footpath Audit:").append(System.lineSeparator());
            text.append("- audit_version: ").append(transferFootpathAudit.auditVersion()).append(System.lineSeparator());
            text.append("- pass: ").append(transferFootpathAudit.pass()).append(System.lineSeparator());
            text.append("- raw_transfers: ").append(transferFootpathAudit.rawTransfers()).append(System.lineSeparator());
            text.append("- scoped_transfers: ").append(transferFootpathAudit.scopedTransfers()).append(System.lineSeparator());
            text.append("- transfer_semantics: ").append(transferFootpathAudit.transferSemanticCounts()).append(System.lineSeparator());
            text.append("- non_pedestrian_gtfs_edges: ").append(transferFootpathAudit.nonPedestrianGtfsEdges()).append(System.lineSeparator());
            text.append("- scoped_gtfs_edges: ").append(transferFootpathAudit.scopedGtfsEdges()).append(System.lineSeparator());
            text.append("- traversable_heuristic_edges: ").append(transferFootpathAudit.traversableHeuristicEdges()).append(System.lineSeparator());
            text.append("- stop_footpaths: ").append(transferFootpathAudit.stopFootpaths()).append(System.lineSeparator());
            text.append("- traversable_stop_footpaths: ").append(transferFootpathAudit.traversableStopFootpaths()).append(System.lineSeparator());
            text.append("- unknown_stop_footpaths: ").append(transferFootpathAudit.unknownStopFootpaths()).append(System.lineSeparator());
            text.append("- oversized_stop_areas: ").append(transferFootpathAudit.oversizedStopAreas()).append(System.lineSeparator());
            text.append("- extreme_stop_areas: ").append(transferFootpathAudit.extremeStopAreas()).append(System.lineSeparator());
            for (String sample : transferFootpathAudit.samples()) {
                text.append("- sample: ").append(sample).append(System.lineSeparator());
            }
        }
        if (sqliteDiagnostics != null) {
            text.append("SQLite Diagnostics:").append(System.lineSeparator());
            text.append("- run_mode: ").append(sqliteDiagnostics.runMode()).append(System.lineSeparator());
            text.append("- derived_builders_skipped: ").append(sqliteDiagnostics.derivedBuildersSkipped()).append(System.lineSeparator());
            if (!sqliteDiagnostics.skippedDerivedBuilders().isEmpty()) {
                text.append("- skipped_derived_builders: ")
                        .append(String.join(", ", sqliteDiagnostics.skippedDerivedBuilders()))
                        .append(System.lineSeparator());
            }
            text.append("- batch_size: ").append(sqliteDiagnostics.batchSize()).append(System.lineSeparator());
            text.append("- stop_times_commit_rows: ").append(sqliteDiagnostics.stopTimesCommitRows()).append(System.lineSeparator());
            text.append("- stop_times_rows: ").append(sqliteDiagnostics.stopTimesRows()).append(System.lineSeparator());
            text.append("- stop_times_write_ms: ").append(sqliteDiagnostics.stopTimesDurationMs()).append(System.lineSeparator());
            text.append("- stop_times_rows_per_second: ").append(sqliteDiagnostics.stopTimesRowsPerSecond()).append(System.lineSeparator());
            text.append("- stop_times_commit_count: ").append(sqliteDiagnostics.stopTimesCommitCount()).append(System.lineSeparator());
            text.append("- stop_times_avg_commit_ms: ").append(sqliteDiagnostics.stopTimesAverageCommitMs()).append(System.lineSeparator());
            text.append("- stop_times_max_commit_ms: ").append(sqliteDiagnostics.stopTimesMaxCommitMs()).append(System.lineSeparator());
            text.append("- sqlite_size_after_stop_times_bytes: ").append(sqliteDiagnostics.sqliteSizeAfterStopTimesBytes()).append(System.lineSeparator());
            text.append("- wal_size_after_stop_times_bytes: ").append(sqliteDiagnostics.walSizeAfterStopTimesBytes()).append(System.lineSeparator());
            if (!sqliteDiagnostics.sqlitePragmas().isEmpty()) {
                text.append("- sqlite_pragmas: ").append(sqliteDiagnostics.sqlitePragmas()).append(System.lineSeparator());
            }
        }
        if (performanceReport != null) {
            text.append("Performance:").append(System.lineSeparator());
            text.append("- total_ms: ").append(performanceReport.totalMs()).append(System.lineSeparator());
            text.append("- used_memory_estimate_mb: ").append(performanceReport.usedMemoryEstimateMb()).append(System.lineSeparator());
            if (!performanceReport.memorySnapshotsMb().isEmpty()) {
                text.append("- memory_note: JVM heap estimates, not OS-resident memory").append(System.lineSeparator());
                for (Map.Entry<String, Long> entry : performanceReport.memorySnapshotsMb().entrySet()) {
                    text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
                }
            }
            for (Map.Entry<String, Long> entry : performanceReport.sectionMs().entrySet()) {
                text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
            }
        }
        if (realFeedValidationReport != null) {
            text.append("RowCounts:").append(System.lineSeparator());
            text.append("- sqlite_file_size_bytes: ").append(realFeedValidationReport.sqliteFileSizeBytes()).append(System.lineSeparator());
            text.append("- warning_total: ").append(realFeedValidationReport.totalWarnings()).append(System.lineSeparator());
            text.append("- critical_warnings: ").append(realFeedValidationReport.criticalWarnings()).append(System.lineSeparator());
            for (Map.Entry<String, Long> entry : realFeedValidationReport.tableRowCounts().entrySet()) {
                text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
            }
        }
        if (warningSummary != null) {
            text.append("Warning Summary:").append(System.lineSeparator());
            for (Map.Entry<String, Integer> entry : warningSummary.counts().entrySet()) {
                text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
            }
        }
        if (indexSmokeChecks != null && !indexSmokeChecks.isEmpty()) {
            text.append("Index Smoke Checks:").append(System.lineSeparator());
            for (Map.Entry<String, String> entry : indexSmokeChecks.entrySet()) {
                text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
            }
        }
        if (serviceDayModelReport != null) {
            text.append("Service Day Model:").append(System.lineSeparator());
            text.append("- model_version: ").append(serviceDayModelReport.modelVersion()).append(System.lineSeparator());
            text.append("- pass: ").append(serviceDayModelReport.pass()).append(System.lineSeparator());
            text.append("- services: ").append(serviceDayModelReport.serviceCount()).append(System.lineSeparator());
            text.append("- trip_services: ").append(serviceDayModelReport.tripServiceCount()).append(System.lineSeparator());
            text.append("- base_calendar_services: ").append(serviceDayModelReport.baseCalendarServiceCount()).append(System.lineSeparator());
            text.append("- exception_services: ").append(serviceDayModelReport.exceptionServiceCount()).append(System.lineSeparator());
            text.append("- exception_only_services: ").append(serviceDayModelReport.exceptionOnlyServiceCount()).append(System.lineSeparator());
            text.append("- unresolved_trip_services: ").append(serviceDayModelReport.unresolvedTripServiceCount()).append(System.lineSeparator());
            text.append("- invalid_weekday_flags: ").append(serviceDayModelReport.invalidWeekdayFlagCount()).append(System.lineSeparator());
            text.append("- invalid_calendar_ranges: ").append(serviceDayModelReport.invalidCalendarRangeCount()).append(System.lineSeparator());
            text.append("- invalid_exception_dates: ").append(serviceDayModelReport.invalidExceptionDateCount()).append(System.lineSeparator());
            text.append("- invalid_exception_types: ").append(serviceDayModelReport.invalidExceptionTypeCount()).append(System.lineSeparator());
            text.append("- overflow_stop_times: ").append(serviceDayModelReport.overflowStopTimeCount()).append(System.lineSeparator());
            text.append("- maximum_service_day_seconds: ").append(serviceDayModelReport.maximumServiceDaySeconds()).append(System.lineSeparator());
            text.append("- service_timezones: ").append(serviceDayModelReport.timezoneCounts()).append(System.lineSeparator());
            for (String sample : serviceDayModelReport.samples()) {
                text.append("- ").append(sample).append(System.lineSeparator());
            }
        }
        if (appReadySqliteReport != null) {
            text.append("App-Ready SQLite:").append(System.lineSeparator());
            text.append("- app_ready: ").append(appReadySqliteReport.appReady()).append(System.lineSeparator());
            for (Map.Entry<String, Boolean> entry : appReadySqliteReport.featureFlags().entrySet()) {
                text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
            }
            for (Map.Entry<String, Long> entry : appReadySqliteReport.qualityChecks().entrySet()) {
                text.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append(System.lineSeparator());
            }
            DisplayNameAuditReport displayNameAudit = appReadySqliteReport.displayNameAudit();
            if (displayNameAudit != null) {
                text.append("Display Name Audit:").append(System.lineSeparator());
                text.append("- audit_version: ").append(displayNameAudit.auditVersion()).append(System.lineSeparator());
                text.append("- pass: ").append(displayNameAudit.pass()).append(System.lineSeparator());
                text.append("- scanned_names: ").append(displayNameAudit.scannedNames()).append(System.lineSeparator());
                text.append("- residual_findings: ").append(displayNameAudit.residualCount()).append(System.lineSeparator());
                text.append("- municipality_only_names: ")
                        .append(displayNameAudit.municipalityOnlyNames())
                        .append(System.lineSeparator());
                text.append("- suspicious_unknown_prefixes: ")
                        .append(displayNameAudit.suspiciousUnknownPrefixes())
                        .append(System.lineSeparator());
                text.append("- transformed_names: ")
                        .append(displayNameAudit.transformedNames())
                        .append(System.lineSeparator());
                text.append("- invalid_transformation_rule_rows: ")
                        .append(displayNameAudit.invalidTransformationRuleRows())
                        .append(System.lineSeparator());
                text.append("- transformation_rule_counts: ")
                        .append(displayNameAudit.transformationRuleCounts())
                        .append(System.lineSeparator());
                for (String sample : displayNameAudit.samples()) {
                    text.append("- ").append(sample).append(System.lineSeparator());
                }
            }
            DisplayNameQualityBaselineReport displayNameQualityBaseline =
                    appReadySqliteReport.displayNameQualityBaseline();
            if (displayNameQualityBaseline != null) {
                text.append("Display Name Quality Baseline:").append(System.lineSeparator());
                text.append("- baseline_version: ")
                        .append(displayNameQualityBaseline.baselineVersion())
                        .append(System.lineSeparator());
                text.append("- pass: ")
                        .append(displayNameQualityBaseline.pass())
                        .append(System.lineSeparator());
                text.append("- findings: ")
                        .append(displayNameQualityBaseline.findingCount())
                        .append(System.lineSeparator());
                text.append("- prefix_findings: ")
                        .append(displayNameQualityBaseline.prefixFindingCount())
                        .append(System.lineSeparator());
                text.append("- municipality_only_findings: ")
                        .append(displayNameQualityBaseline.municipalityOnlyFindingCount())
                        .append(System.lineSeparator());
                text.append("- coverage_gaps: ")
                        .append(displayNameQualityBaseline.coverageGapCount())
                        .append(System.lineSeparator());
                text.append("- destructive_actions: ")
                        .append(displayNameQualityBaseline.destructiveActionCount())
                        .append(System.lineSeparator());
                text.append("- classifications: ")
                        .append(displayNameQualityBaseline.classificationCounts())
                        .append(System.lineSeparator());
            }
            for (String warning : appReadySqliteReport.warnings()) {
                text.append("- WARN ").append(warning).append(System.lineSeparator());
            }
        }
        if (!warnings.isEmpty()) {
            text.append("Warnings:").append(System.lineSeparator());
            for (String warning : warnings) {
                text.append("- ").append(warning).append(System.lineSeparator());
            }
        }
        return text.toString();
    }

    private static void appendOmittedCount(StringBuilder text, int total, int shown) {
        if (total > shown) {
            text.append("- ... +")
                    .append(total - shown)
                    .append(" more")
                    .append(System.lineSeparator());
        }
    }

    public static Builder builder(Path inputZip, Path outputDatabase) {
        return new Builder(inputZip, outputDatabase);
    }

    public static final class Builder {
        private final Path inputZip;
        private final Path outputDatabase;
        private long stops;
        private long routes;
        private long trips;
        private long stopTimes;
        private long transfers;
        private long calendarRows;
        private long calendarDateRows;
        private long stopAreas;
        private long searchTokens;
        private StopAreaReporter.StopAreaStats stopAreaStats;
        private HubProfileBuilder.HubProfileStats hubProfileStats;
        private RouteAxisBuilder.RouteAxisStats routeAxisStats;
        private TransferRuleBuilder.TransferRuleStats transferRuleStats;
        private StopFootpathBuilder.StopFootpathStats stopFootpathStats;
        private RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingCompatibilityAudit;
        private TransferFootpathAuditReport transferFootpathAudit;
        private SqliteContractReport contractReport;
        private PerformanceReport performanceReport;
        private WarningSummary warningSummary;
        private Map<String, String> indexSmokeChecks;
        private SqliteDiagnosticsReport sqliteDiagnostics;
        private AppReadySqliteReport appReadySqliteReport;
        private ServiceDayModelReport serviceDayModelReport;
        private RealFeedValidationReport realFeedValidationReport;
        private final List<String> warnings = new ArrayList<>();

        private Builder(Path inputZip, Path outputDatabase) {
            this.inputZip = inputZip;
            this.outputDatabase = outputDatabase;
        }

        public Builder stops(long stops) {
            this.stops = stops;
            return this;
        }

        public Builder routes(long routes) {
            this.routes = routes;
            return this;
        }

        public Builder trips(long trips) {
            this.trips = trips;
            return this;
        }

        public Builder stopTimes(long stopTimes) {
            this.stopTimes = stopTimes;
            return this;
        }

        public Builder transfers(long transfers) {
            this.transfers = transfers;
            return this;
        }

        public Builder calendarRows(long calendarRows) {
            this.calendarRows = calendarRows;
            return this;
        }

        public Builder calendarDateRows(long calendarDateRows) {
            this.calendarDateRows = calendarDateRows;
            return this;
        }

        public Builder stopAreas(long stopAreas) {
            this.stopAreas = stopAreas;
            return this;
        }

        public Builder searchTokens(long searchTokens) {
            this.searchTokens = searchTokens;
            return this;
        }

        public Builder stopAreaStats(StopAreaReporter.StopAreaStats stopAreaStats) {
            this.stopAreaStats = stopAreaStats;
            return this;
        }

        public Builder hubProfileStats(HubProfileBuilder.HubProfileStats hubProfileStats) {
            this.hubProfileStats = hubProfileStats;
            return this;
        }

        public Builder routeAxisStats(RouteAxisBuilder.RouteAxisStats routeAxisStats) {
            this.routeAxisStats = routeAxisStats;
            return this;
        }

        public Builder transferRuleStats(TransferRuleBuilder.TransferRuleStats transferRuleStats) {
            this.transferRuleStats = transferRuleStats;
            return this;
        }

        public Builder stopFootpathStats(StopFootpathBuilder.StopFootpathStats stopFootpathStats) {
            this.stopFootpathStats = stopFootpathStats;
            return this;
        }

        public Builder routingCompatibilityAudit(RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingCompatibilityAudit) {
            this.routingCompatibilityAudit = routingCompatibilityAudit;
            return this;
        }

        public Builder transferFootpathAudit(TransferFootpathAuditReport transferFootpathAudit) {
            this.transferFootpathAudit = transferFootpathAudit;
            return this;
        }

        public Builder contractReport(SqliteContractReport contractReport) {
            this.contractReport = contractReport;
            return this;
        }

        public Builder performanceReport(PerformanceReport performanceReport) {
            this.performanceReport = performanceReport;
            return this;
        }

        public Builder warningSummary(WarningSummary warningSummary) {
            this.warningSummary = warningSummary;
            return this;
        }

        public Builder indexSmokeChecks(Map<String, String> indexSmokeChecks) {
            this.indexSmokeChecks = indexSmokeChecks;
            return this;
        }

        public Builder sqliteDiagnostics(SqliteDiagnosticsReport sqliteDiagnostics) {
            this.sqliteDiagnostics = sqliteDiagnostics;
            return this;
        }

        public Builder appReadySqliteReport(AppReadySqliteReport appReadySqliteReport) {
            this.appReadySqliteReport = appReadySqliteReport;
            return this;
        }

        public Builder serviceDayModelReport(ServiceDayModelReport serviceDayModelReport) {
            this.serviceDayModelReport = serviceDayModelReport;
            return this;
        }

        public Builder realFeedValidationReport(RealFeedValidationReport realFeedValidationReport) {
            this.realFeedValidationReport = realFeedValidationReport;
            return this;
        }

        public Builder warning(String warning) {
            warnings.add(warning);
            return this;
        }

        public List<String> warningsSnapshot() {
            return List.copyOf(warnings);
        }

        public PreprocessReport build() {
            return new PreprocessReport(
                    inputZip,
                    outputDatabase,
                    stops,
                    routes,
                    trips,
                    stopTimes,
                    transfers,
                    calendarRows,
                    calendarDateRows,
                    stopAreas,
                    searchTokens,
                    stopAreaStats,
                    hubProfileStats,
                    routeAxisStats,
                    transferRuleStats,
                    stopFootpathStats,
                    routingCompatibilityAudit,
                    transferFootpathAudit,
                    contractReport,
                    performanceReport,
                    warningSummary,
                    indexSmokeChecks == null ? Map.of() : Map.copyOf(indexSmokeChecks),
                    sqliteDiagnostics,
                    appReadySqliteReport,
                    serviceDayModelReport,
                    realFeedValidationReport,
                    List.copyOf(warnings)
            );
        }
    }
}
