package de.ixit.gtfs;

import de.ixit.gtfs.model.AreaRouteServiceSummary;
import de.ixit.gtfs.model.CanonicalStopArea;
import de.ixit.gtfs.model.CanonicalStopAreaMember;
import de.ixit.gtfs.model.CanonicalStopAreaTransferEdge;
import de.ixit.gtfs.model.FeedInfo;
import de.ixit.gtfs.model.HubProfile;
import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.Agency;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopAreaAlias;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaCity;
import de.ixit.gtfs.model.StopAreaProfile;
import de.ixit.gtfs.model.TransferEdge;
import de.ixit.gtfs.model.TransferRule;
import de.ixit.gtfs.model.Trip;
import de.ixit.gtfs.model.Pathway;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class GtfsPreprocessor {
    private static final long STREAMING_HEAP_GUARD_INTERVAL_ROWS = 100_000;

    public PreprocessReport run(Path inputZip, Path outputDatabase) throws IOException, SQLException {
        return run(inputZip, outputDatabase, null);
    }

    public PreprocessReport run(Path inputZip, Path outputDatabase, Path reportOutput) throws IOException, SQLException {
        return run(inputZip, outputDatabase, reportOutput, PreprocessOptions.defaults());
    }

    public PreprocessReport run(Path inputZip, Path outputDatabase, Path reportOutput, PreprocessOptions options) throws IOException, SQLException {
        PreprocessOptions effectiveOptions = options == null ? PreprocessOptions.defaults() : options;
        PerformanceTracker performance = new PerformanceTracker();
        WarningSummary warningSummary = new WarningSummary();

        if (!Files.isRegularFile(inputZip)) {
            throw new IllegalArgumentException("GTFS ZIP does not exist: " + inputZip);
        }
        BuildIdentity buildIdentity = BuildIdentity.capture(inputZip, effectiveOptions);

        Path parent = outputDatabase.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        deleteSqliteOutput(outputDatabase);

        PreprocessReport.Builder report = PreprocessReport.builder(inputZip, outputDatabase);
        HubProfileBuilder.HubProfileStats hubProfileStats = null;
        RouteAxisBuilder.RouteAxisStats routeAxisStats = null;
        TransferRuleBuilder.TransferRuleStats transferRuleStats = null;
        TransferEdgeBuilder.TransferEdgeStats transferEdgeStats = null;
        StopFootpathBuilder.StopFootpathStats stopFootpathStats = null;
        SqliteDiagnosticsReport sqliteDiagnostics = null;
        long calendarRowCount = 0;
        long calendarDateRowCount = 0;
        long shapePointCount = 0;

        try (GtfsZipReader gtfs = GtfsZipReader.open(inputZip);
             SqliteGtfsWriter writer = SqliteGtfsWriter.create(outputDatabase)) {
            Optional<FeedInfo> feedInfo;
            SqliteGtfsWriter.MetadataDiagnostics metadataDiagnostics;

            {
            List<Stop> stops = measureIo(performance, "parse_stops_ms", () -> GtfsParsers.readStops(gtfs.openRequired("stops.txt")));
            List<StopArea> stopAreas = measureSql(performance, "build_stop_areas_ms", () -> StopAreaBuilder.fromStops(stops));
            StopAreaCityBuilder.StopAreaCityBuildResult stopAreaCityResult = measureIo(
                    performance,
                    "build_stop_area_cities_ms",
                    () -> StopAreaCityBuilder.build(
                            stopAreas,
                            effectiveOptions.municipalityGeoJson(),
                            effectiveOptions.municipalityDataVersion()
                    )
            );
            List<StopAreaCity> stopAreaCities = stopAreaCityResult.cities();
            List<Agency> agencies = gtfs.exists("agency.txt")
                    ? measureIo(performance, "parse_agencies_ms", () -> GtfsParsers.readAgencies(gtfs.openRequired("agency.txt")))
                    : List.of();
            List<Route> parsedRoutes = measureIo(performance, "parse_routes_ms", () -> GtfsParsers.readRoutes(gtfs.openRequired("routes.txt")));
            VbbRouteColorEnricher.Result colorResult = measureIo(
                    performance,
                    "enrich_vbb_route_colors_ms",
                    () -> VbbRouteColorEnricher.enrich(parsedRoutes, agencies)
            );
            RnvRouteColorEnricher.Result rnvColorResult = measureIo(
                    performance,
                    "enrich_rnv_route_colors_ms",
                    () -> RnvRouteColorEnricher.enrich(colorResult.routes(), agencies)
            );
            List<Route> routes = rnvColorResult.routes();
            List<Trip> trips = measureIo(performance, "parse_trips_ms", () -> GtfsParsers.readTrips(gtfs.openRequired("trips.txt")));
            feedInfo = gtfs.exists("feed_info.txt")
                    ? measureIo(performance, "parse_feed_info_ms", () -> GtfsParsers.readFeedInfo(gtfs.openRequired("feed_info.txt")))
                    : Optional.empty();
            StopAreaReporter.StopAreaStats stopAreaStats = StopAreaReporter.summarize(stops, stopAreas);
            StopAreaAliasBuilder.StopAreaAliasBuildResult aliasBuildResult = measureSql(
                    performance,
                    "build_stop_area_aliases_ms",
                    () -> StopAreaAliasBuilder.build(stops, stopAreas, stopAreaCities)
            );
            List<StopAreaAlias> stopAreaAliases = aliasBuildResult.aliases();
            RouteAxisBuilder routeAxisBuilder = effectiveOptions.buildRouteAxes() ? new RouteAxisBuilder(stops, routes, trips) : null;
            AreaRouteServiceSummaryBuilder areaRouteServiceSummaryBuilder = effectiveOptions.skipDerivedBuilders() ? null : new AreaRouteServiceSummaryBuilder(stops, routes, trips);

            compactHeapAtPhaseBoundary(performance, "sqlite_core_build");
            measureSqlWithProgress(performance, "write_sqlite_core_ms", "sqlite_core_writes", () -> {
                writer.writeStops(stops);
                writer.writeStopAreas(stopAreas);
                writer.writeStopAreaCities(stopAreaCities);
                writer.writeStopAreaMembers(stops);
                writer.writeAgencies(agencies);
                writer.writeRoutes(routes);
                writer.writeTrips(trips);
                writer.writeStopAreaAliases(stopAreaAliases);
            });
            compactHeapAtPhaseBoundary(performance, "sqlite_core_writes");

            int searchTokenCount = buildAndWriteSearchTokens(
                    performance,
                    writer,
                    report,
                    warningSummary
            );

            report.stops(stops.size())
                    .stopAreas(stopAreas.size())
                    .stopAreaStats(stopAreaStats)
                    .searchTokens(searchTokenCount)
                    .routes(routes.size())
                    .trips(trips.size());
            if (colorResult.appliedCount() > 0) {
                report.warning("VBB route colors added for " + colorResult.appliedCount() + " routes");
            }
            if (rnvColorResult.appliedCount() > 0) {
                report.warning("RNV route colors added for " + rnvColorResult.appliedCount() + " routes");
            }
            StopAreaCityBuilder.StopAreaCityStats cityStats = stopAreaCityResult.stats();
            if (effectiveOptions.municipalityGeoJson() != null) {
                report.warning("Municipality resolution "
                        + cityStats.officialBoundaryCount()
                        + "/"
                        + cityStats.totalCount()
                        + " official, "
                        + cityStats.nameFallbackCount()
                        + " name fallback, "
                        + cityStats.unresolvedCount()
                        + " unresolved; version="
                        + cityStats.municipalityDataVersion());
            }

            addStopQualityWarnings(stops, report, warningSummary);
            addTripQualityWarnings(trips, routes, report, warningSummary);
            addStopAreaQualityWarnings(stopAreaStats, report, warningSummary);

            compactHeapAtPhaseBoundary(performance, "stop_search_tokens");
            performance.snapshotMemory("before_stop_times_mb");
            SqliteGtfsWriter.StopTimesWriteReport stopTimesReport = measureIo(performance, "parse_write_stop_times_ms", () -> writer.writeStopTimes(
                    gtfs.openRequired("stop_times.txt"),
                    stops.stream().map(Stop::stopId).collect(Collectors.toUnmodifiableSet()),
                    areaRouteServiceSummaryBuilder == null ? null : areaRouteServiceSummaryBuilder::observeStopTime,
                    heapGuardedProgressLogger("stop_times", 500_000)
            ));
            performance.snapshotMemory("after_stop_times_mb");
            sqliteDiagnostics = SqliteDiagnosticsReport.from(effectiveOptions, stopTimesReport);
            report.sqliteDiagnostics(sqliteDiagnostics);
            report.stopTimes(stopTimesReport.rows());
            if (stopTimesReport.unknownStopReferences() > 0) {
                warningSummary.set("unknown_stop_references", Math.toIntExact(Math.min(Integer.MAX_VALUE, stopTimesReport.unknownStopReferences())));
                report.warning("StopTime references unknown stop_id "
                        + stopTimesReport.unknownStopReferences()
                        + " time(s), samples: "
                        + String.join(", ", stopTimesReport.unknownStopSamples()));
            }

            if (gtfs.exists("shapes.txt")) {
                shapePointCount = measureIo(
                        performance,
                        "parse_write_shapes_ms",
                        () -> writer.writeShapes(
                                gtfs.openRequired("shapes.txt"),
                                progressLogger("shapes", 500_000)
                        )
                );
            } else {
                report.warning("Optional GTFS file missing: shapes.txt");
            }

            if (gtfs.exists("calendar.txt")) {
                calendarRowCount = measureIo(
                        performance,
                        "parse_write_calendar_ms",
                        () -> writer.writeCalendar(
                                gtfs.openRequired("calendar.txt"),
                                progressLogger("calendar", 500_000)
                        )
                );
                report.calendarRows(calendarRowCount);
            } else {
                report.warning("Optional GTFS file missing: calendar.txt");
            }

            if (gtfs.exists("calendar_dates.txt")) {
                calendarDateRowCount = measureIo(
                        performance,
                        "parse_write_calendar_dates_ms",
                        () -> writer.writeCalendarDates(
                                gtfs.openRequired("calendar_dates.txt"),
                                heapGuardedProgressLogger("calendar_dates", 500_000)
                        )
                );
                report.calendarDateRows(calendarDateRowCount);
            } else {
                report.warning("Optional GTFS file missing: calendar_dates.txt");
            }

            compactHeapAtPhaseBoundary(performance, "calendar_imports");
            measureSqlWithProgress(
                    performance,
                    "build_service_calendar_summary_ms",
                    "service_calendar_summary",
                    writer::buildServiceCalendarSummary
            );

            List<Pathway> pathways = gtfs.exists("pathways.txt")
                    ? measureIo(performance, "parse_pathways_ms", () -> GtfsParsers.readPathways(gtfs.openRequired("pathways.txt")))
                    : List.of();
            measureSql(performance, "write_pathways_ms", () -> writer.writePathways(pathways));
            if (!gtfs.exists("pathways.txt")) {
                report.warning("Optional GTFS file missing: pathways.txt; station walks use explicitly labelled geometry estimates only.");
            }

            if (effectiveOptions.skipDerivedBuilders()) {
                importTransfersWithoutDerivedData(gtfs, writer, performance, report);
                warningSummary.increment("derived_builders_skipped");
                report.warning("Diagnostic mode CORE_ONLY skipped derived builders: "
                        + String.join(", ", effectiveOptions.skippedDerivedBuilders())
                        + ". SQLite contract tables remain present but derived rows are incomplete.");
                logSectionSkipped("hub_profile_sql", effectiveOptions.runMode());
                logSectionSkipped("area_route_service_summary", effectiveOptions.runMode());
                logSectionSkipped("stop_area_profiles", effectiveOptions.runMode());
                logSectionSkipped("canonical_stop_areas", effectiveOptions.runMode());
                logSectionSkipped("route_axis_sql", effectiveOptions.runMode());
                logSectionSkipped("transfer_rules", effectiveOptions.runMode());
                logSectionSkipped("transfer_edges", effectiveOptions.runMode());
            } else {
                {
                    AreaRouteServiceSummaryBuilder.AreaRouteServiceSummaryBuildResult areaRouteServiceSummaryResult = measureSql(
                            performance,
                            "build_area_route_service_summary_ms",
                            areaRouteServiceSummaryBuilder::build
                    );
                    List<AreaRouteServiceSummary> areaRouteServiceSummaries = areaRouteServiceSummaryResult.summaries();
                    measureSqlWithProgress(
                            performance,
                            "write_area_route_service_summary_ms",
                            "area_route_service_summary_write",
                            () -> writer.writeAreaRouteServiceSummaries(areaRouteServiceSummaries)
                    );
                }
                compactHeapAtPhaseBoundary(performance, "area_route_service_summary");

                buildAndWriteCanonicalStopAreas(
                        performance,
                        writer,
                        outputDatabase,
                        stops,
                        stopAreas,
                        stopAreaAliases,
                        stopAreaCities
                );
                compactHeapAtPhaseBoundary(performance, "stop_area_profiles_and_canonical_areas");

                buildAndWriteDisplayQualityBaseline(performance, writer, outputDatabase);
                compactHeapAtPhaseBoundary(performance, "display_quality_baseline");

                if (effectiveOptions.buildRouteAxes()) {
                    measureSqlWithProgress(performance, "create_route_axis_source_indexes_ms", "route_axis_source_indexes", writer::createRouteAxisSourceIndexes);

                    routeAxisStats = measureSqlWithProgress(performance, "build_write_route_axes_ms", "route_axis_sql_build_write",
                            () -> writer.writeRouteAxes(routeAxisBuilder,
                                    heapGuardedProgressLogger("route_axis_scan", 500_000, derivedHeapGuardThresholdMb()),
                                    heapGuardedProgressLogger("route_axis_write", 500_000, derivedHeapGuardThresholdMb())));
                    report.routeAxisStats(routeAxisStats);
                    addRouteAxisQualityWarnings(routeAxisStats, report, warningSummary);
                    performance.snapshotMemory("after_route_axes_mb");
                    compactHeapAtPhaseBoundary(performance, "route_axes");
                } else {
                    warningSummary.increment("route_axes_skipped_app_runtime");
                    report.warning("APP_RUNTIME skipped route_axes builder; Android runtime does not require route_axes for StopSearch or routing.");
                    logSectionSkipped("route_axis_sql", effectiveOptions.runMode());
                }

                TransferDerivedBuildResult transferResult = buildAndWriteTransferDerivedData(
                        gtfs,
                        writer,
                        performance,
                        stops,
                        stopAreas,
                        pathways,
                        report
                );
                transferRuleStats = transferResult.transferRuleStats();
                transferEdgeStats = transferResult.transferEdgeStats();
                stopFootpathStats = transferResult.stopFootpathStats();
                report.transferRuleStats(transferRuleStats);
                report.stopFootpathStats(stopFootpathStats);
                addTransferRuleQualityWarnings(transferRuleStats, report, warningSummary);
                compactHeapAtPhaseBoundary(performance, "transfer_rules_and_edges");

                HubProfileBuilder.HubProfileBuildResult hubProfileResult = measureSqlWithProgress(
                        performance,
                        "build_hub_profiles_ms",
                        "hub_profile_sql_build",
                        () -> HubProfileBuilder.databaseBacked(stops, stopAreas).buildFromDatabase(outputDatabase)
                );
                List<HubProfile> hubProfiles = hubProfileResult.profiles();
                compactHeapAtPhaseBoundary(performance, "hub_profiles_build");
                measureSqlWithProgress(performance, "write_hub_profiles_ms", "hub_profile_sql_write", () -> writer.writeHubProfiles(hubProfiles));
                hubProfileStats = hubProfileResult.stats();
                report.hubProfileStats(hubProfileStats);
                addHubProfileQualityWarnings(hubProfiles, report);
                compactHeapAtPhaseBoundary(performance, "hub_profiles");
            }

            metadataDiagnostics = new SqliteGtfsWriter.MetadataDiagnostics(
                    Files.size(inputZip),
                    stops.size(),
                    stopAreas.size(),
                    trips.size(),
                    stopTimesReport.rows(),
                    shapePointCount,
                    calendarRowCount,
                    calendarDateRowCount,
                    routes.size(),
                    cityStats.officialBoundaryCount(),
                    cityStats.nameFallbackCount(),
                    cityStats.unresolvedCount(),
                    cityStats.municipalityDataVersion()
            );
            }

            compactHeapAtPhaseBoundary(performance, "import_model");
            measureSqlWithProgress(
                    performance,
                    "create_indexes_ms",
                    effectiveOptions.useAppRuntimeIndexes() ? "app_runtime_index_creation" : "index_creation",
                    effectiveOptions.useAppRuntimeIndexes() ? writer::createAppRuntimeIndexes : writer::createIndexes
            );
            performance.snapshotMemory("after_sqlite_indexes_mb");
            measureSql(performance, "write_metadata_ms", () -> writer.writeMetadata(
                    feedInfo,
                    metadataDiagnostics,
                    effectiveOptions.contractVersion(),
                    buildIdentity
            ));
        }

        SqliteContractReport contractReport = measureSqlWithProgress(performance, "contract_validation_ms", "contract_validation", () -> SqliteContractValidator.validate(outputDatabase));
        SqliteContractReport enrichedContractReport = new SqliteContractReport(
                contractReport.schemaVersion(),
                contractReport.preprocessorVersion(),
                contractReport.contractName(),
                contractReport.contractVersion(),
                contractReport.metadata(),
                contractReport.tables(),
                contractReport.indexes(),
                contractReport.rowCounts(),
                hubProfileStats == null ? contractReport.hubProfileStats() : hubProfileStats,
                routeAxisStats == null ? contractReport.routeAxisStats() : routeAxisStats,
                transferRuleStats == null ? contractReport.transferRuleStats() : transferRuleStats,
                transferEdgeStats == null ? contractReport.transferEdgeStats() : transferEdgeStats,
                contractReport.timeModel(),
                contractReport.stopIdPolicy(),
                contractReport.areaIdPolicy(),
                contractReport.searchTokensPolicy()
        );
        ServiceDayModelReport serviceDayModelReport = measureSqlWithProgress(
                performance,
                "service_day_model_audit_ms",
                "service_day_model_audit",
                () -> ServiceDayModelAuditor.audit(outputDatabase)
        );
        RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingAudit = measureSqlWithProgress(
                performance,
                "routing_compatibility_audit_ms",
                "routing_audit",
                () -> RoutingCompatibilityAuditor.audit(outputDatabase, enrichedContractReport, serviceDayModelReport)
        );
        TransferFootpathAuditReport transferFootpathAudit = measureSqlWithProgress(
                performance,
                "transfer_footpath_audit_ms",
                "transfer_footpath_audit",
                () -> TransferFootpathAuditor.audit(outputDatabase)
        );
        report.routingCompatibilityAudit(routingAudit);
        report.transferFootpathAudit(transferFootpathAudit);
        report.contractReport(enrichedContractReport);
        Map<String, String> indexSmokeChecks = measureSql(performance, "index_smoke_checks_ms", () -> IndexSmokeChecker.run(outputDatabase));
        AppReadySqliteReport appReadySqliteReport = measureSqlWithProgress(
                performance,
                "app_ready_sqlite_validation_ms",
                "app_ready_sqlite_validation",
                () -> AppReadySqliteValidator.validate(outputDatabase, serviceDayModelReport)
        );
        Path jsonReportPath = reportOutput == null ? contractReportPath(outputDatabase) : reportOutput;
        PerformanceReport performanceReport = performance.snapshot();
        RealFeedValidationReport initialRealFeedValidation = buildRealFeedValidation(
                inputZip,
                outputDatabase,
                performanceReport,
                enrichedContractReport,
                warningSummary,
                report.warningsSnapshot().size(),
                indexSmokeChecks,
                routingAudit,
                sqliteDiagnostics
        );
        measureIoWithProgress(performance, "json_report_ms", "json_report", () -> {
            SqliteContractJsonWriter.write(jsonReportPath, enrichedContractReport, routingAudit, transferFootpathAudit, initialRealFeedValidation, appReadySqliteReport, serviceDayModelReport, report.warningsSnapshot());
            return null;
        });
        performanceReport = performance.snapshot();
        RealFeedValidationReport realFeedValidation = buildRealFeedValidation(
                inputZip,
                outputDatabase,
                performanceReport,
                enrichedContractReport,
                warningSummary,
                report.warningsSnapshot().size(),
                indexSmokeChecks,
                routingAudit,
                sqliteDiagnostics
        );
        report.performanceReport(performanceReport)
                .warningSummary(warningSummary)
                .indexSmokeChecks(indexSmokeChecks)
                .sqliteDiagnostics(sqliteDiagnostics)
                .appReadySqliteReport(appReadySqliteReport)
                .serviceDayModelReport(serviceDayModelReport)
                .realFeedValidationReport(realFeedValidation);
        SqliteContractJsonWriter.write(jsonReportPath, enrichedContractReport, routingAudit, transferFootpathAudit, realFeedValidation, appReadySqliteReport, serviceDayModelReport, report.warningsSnapshot());
        PreprocessReport builtReport = report.build();
        enforceAppRuntimeReadiness(effectiveOptions, builtReport.appReadySqliteReport());
        return builtReport;
    }

    private static void enforceAppRuntimeReadiness(PreprocessOptions options, AppReadySqliteReport appReadySqliteReport) {
        if (!options.requireAppReady()) {
            return;
        }
        if (appReadySqliteReport == null) {
            throw new IllegalStateException("APP_RUNTIME SQLite is not app-ready: app-ready report missing");
        }
        if (appReadySqliteReport.appReady()) {
            return;
        }
        String warnings = appReadySqliteReport.warnings().isEmpty()
                ? "unknown app-ready validation failure"
                : String.join("; ", appReadySqliteReport.warnings());
        throw new IllegalStateException("APP_RUNTIME SQLite is not app-ready: " + warnings);
    }

    private static void importTransfersWithoutDerivedData(
            GtfsZipReader gtfs,
            SqliteGtfsWriter writer,
            PerformanceTracker performance,
            PreprocessReport.Builder report
    ) throws IOException, SQLException {
        if (gtfs.exists("transfers.txt")) {
            report.transfers(measureIo(
                    performance,
                    "parse_write_transfers_ms",
                    () -> writer.writeTransfers(gtfs.openRequired("transfers.txt"), null)
            ));
        } else {
            report.warning("Optional GTFS file missing: transfers.txt");
        }
    }

    private static void buildAndWriteCanonicalStopAreas(
            PerformanceTracker performance,
            SqliteGtfsWriter writer,
            Path outputDatabase,
            List<Stop> stops,
            List<StopArea> stopAreas,
            List<StopAreaAlias> stopAreaAliases,
            List<StopAreaCity> stopAreaCities
    ) throws SQLException {
        StopAreaProfileBuilder.StopAreaProfileBuildResult stopAreaProfileResult = measureSqlWithProgress(
                performance,
                "build_stop_area_profiles_ms",
                "stop_area_profiles_build",
                () -> new StopAreaProfileBuilder(stops, stopAreas).buildFromDatabase(outputDatabase)
        );
        List<StopAreaProfile> stopAreaProfiles = stopAreaProfileResult.profiles();
        compactHeapAtPhaseBoundary(performance, "stop_area_profiles_build");
        measureSqlWithProgress(
                performance,
                "write_stop_area_profiles_ms",
                "stop_area_profiles_write",
                () -> writer.writeStopAreaProfiles(stopAreaProfiles)
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult canonicalStopAreaResult = measureSqlWithProgress(
                performance,
                "build_canonical_stop_areas_ms",
                "canonical_stop_areas_build",
                () -> new CanonicalStopAreaBuilder(
                        stopAreas,
                        stopAreaProfiles,
                        stopAreaAliases,
                        stopAreaCities
                ).build()
        );
        List<CanonicalStopArea> canonicalStopAreas = canonicalStopAreaResult.canonicalAreas();
        List<CanonicalStopAreaMember> canonicalStopAreaMembers = canonicalStopAreaResult.members();
        List<CanonicalStopAreaTransferEdge> canonicalStopAreaTransferEdges = canonicalStopAreaResult.transferEdges();
        compactHeapAtPhaseBoundary(performance, "canonical_stop_areas_build");
        measureSqlWithProgress(
                performance,
                "write_canonical_stop_areas_ms",
                "canonical_stop_areas_write",
                () -> writer.writeCanonicalStopAreas(
                        canonicalStopAreas,
                        canonicalStopAreaMembers,
                        canonicalStopAreaTransferEdges,
                        stopAreas,
                        stopAreaCities
                )
        );
    }

    private static void buildAndWriteDisplayQualityBaseline(
            PerformanceTracker performance,
            SqliteGtfsWriter writer,
            Path outputDatabase
    ) throws SQLException {
        DisplayNameQualityBaselineBuilder.BuildResult displayQualityBaseline = measureSqlWithProgress(
                performance,
                "build_display_name_quality_baseline_ms",
                "display_name_quality_baseline_build",
                () -> DisplayNameQualityBaselineBuilder.buildFromDatabase(outputDatabase,
                        heapGuardedProgressLogger("display_name_quality", 100_000, derivedHeapGuardThresholdMb()))
        );
        measureSqlWithProgress(
                performance,
                "write_display_name_quality_baseline_ms",
                "display_name_quality_baseline_write",
                () -> writer.writeDisplayNameQualityFindings(displayQualityBaseline.findings())
        );
    }

    private static TransferDerivedBuildResult buildAndWriteTransferDerivedData(
            GtfsZipReader gtfs,
            SqliteGtfsWriter writer,
            PerformanceTracker performance,
            List<Stop> stops,
            List<StopArea> stopAreas,
            List<Pathway> pathways,
            PreprocessReport.Builder report
    ) throws IOException, SQLException {
        TransferRuleBuilder transferRuleBuilder = new TransferRuleBuilder(stops);
        if (gtfs.exists("transfers.txt")) {
            report.transfers(measureIo(
                    performance,
                    "parse_write_transfers_ms",
                    () -> writer.writeTransfers(
                            gtfs.openRequired("transfers.txt"),
                            transferRuleBuilder::observeGtfsTransfer
                    )
            ));
        } else {
            report.warning("Optional GTFS file missing: transfers.txt");
        }

        compactHeapAtPhaseBoundary(performance, "transfer_import");
        TransferRuleBuilder.TransferRuleBuildResult transferRuleResult = measureSqlWithProgress(
                performance,
                "build_transfer_rules_ms",
                "transfer_rules_build",
                transferRuleBuilder::build
        );
        List<TransferRule> transferRules = transferRuleResult.rules();
        measureSqlWithProgress(
                performance,
                "write_transfer_rules_ms",
                "transfer_rules_write",
                () -> writer.writeTransferRules(transferRules)
        );

        TransferEdgeBuilder transferEdgeBuilder = new TransferEdgeBuilder(stops, stopAreas, pathways);
        TransferEdgeBuilder.TransferEdgeStats transferEdgeStats = measureSqlWithProgress(
                performance,
                "build_write_transfer_edges_ms",
                "transfer_edges_build_write",
                () -> writer.writeTransferEdges(transferEdgeBuilder, transferRules)
        );
        StopFootpathBuilder footpathBuilder = new StopFootpathBuilder(stops, pathways, transferRules);
        if (footpathBuilder.unusablePathwayRows() > 0) {
            report.warning("Unusable pathway rows: " + footpathBuilder.unusablePathwayRows()
                    + "; missing/invalid timing, direction or station endpoints. Affected stations do not get geometry shortcuts.");
        }
        StopFootpathBuilder.StopFootpathStats stopFootpathStats = measureSqlWithProgress(
                performance,
                "build_write_stop_footpaths_ms",
                "stop_footpaths_build_write",
                () -> writer.writeStopFootpaths(footpathBuilder)
        );
        return new TransferDerivedBuildResult(transferRuleResult.stats(), transferEdgeStats, stopFootpathStats);
    }

    private record TransferDerivedBuildResult(
            TransferRuleBuilder.TransferRuleStats transferRuleStats,
            TransferEdgeBuilder.TransferEdgeStats transferEdgeStats,
            StopFootpathBuilder.StopFootpathStats stopFootpathStats
    ) {
    }

    private static Path contractReportPath(Path outputDatabase) {
        Path parent = outputDatabase.toAbsolutePath().getParent();
        if (parent == null) {
            parent = Path.of("").toAbsolutePath();
        }
        return parent.resolve("ixit_gtfs_contract_report.json");
    }

    private static void deleteSqliteOutput(Path outputDatabase) throws IOException {
        Files.deleteIfExists(outputDatabase);
        Files.deleteIfExists(Path.of(outputDatabase.toString() + "-wal"));
        Files.deleteIfExists(Path.of(outputDatabase.toString() + "-shm"));
    }

    private static int buildAndWriteSearchTokens(
            PerformanceTracker performance,
            SqliteGtfsWriter writer,
            PreprocessReport.Builder report,
            WarningSummary warningSummary
    ) throws SQLException {
        StopSearchTokenBuilder.StreamingStats result = measureSqlWithProgress(
                performance,
                "build_write_stop_search_tokens_ms",
                "stop_search_token_build_write",
                () -> writer.writeStopSearchTokens(heapGuardedProgressLogger(
                        "stop_search_tokens", 100_000, derivedHeapGuardThresholdMb()))
        );
        addSearchTokenQualityWarnings(result, report, warningSummary);
        return result.tokenCount();
    }

    private static void compactHeapAtPhaseBoundary(PerformanceTracker performance, String phase) {
        long beforeMb = PerformanceTracker.usedMemoryMb();
        performance.snapshotMemory("before_" + phase + "_release_mb");
        System.gc();
        long afterMb = PerformanceTracker.usedMemoryMb();
        performance.snapshotMemory("after_" + phase + "_release_mb");
        System.err.println("[IXIT GTFS Preprocessor] section=heap_phase_release phase="
                + phase
                + " before_mb="
                + beforeMb
                + " after_mb="
                + afterMb
                + " reclaimed_mb="
                + Math.max(0, beforeMb - afterMb));
    }

    private static long logSectionStart(String section) {
        System.err.println("[IXIT GTFS Preprocessor] section="
                + section
                + " status=start memory_used_mb="
                + PerformanceTracker.usedMemoryMb());
        return System.nanoTime();
    }

    private static void logSectionEnd(String section, long startedNanos) {
        long elapsedMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        System.err.println("[IXIT GTFS Preprocessor] section="
                + section
                + " status=end elapsed_ms="
                + elapsedMs
                + " memory_used_mb="
                + PerformanceTracker.usedMemoryMb());
    }

    private static void logSectionFailed(String section, long startedNanos, Exception ex) {
        long elapsedMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        System.err.println("[IXIT GTFS Preprocessor] section="
                + section
                + " status=failed elapsed_ms="
                + elapsedMs
                + " error="
                + ex.getClass().getSimpleName()
                + " memory_used_mb="
                + PerformanceTracker.usedMemoryMb());
    }

    private static void logSectionSkipped(String section, String mode) {
        System.err.println("[IXIT GTFS Preprocessor] section="
                + section
                + " status=skipped mode="
                + mode
                + " memory_used_mb="
                + PerformanceTracker.usedMemoryMb());
    }

    private static GtfsCsvReader.ProgressListener progressLogger(String section, long intervalRows) {
        long started = System.nanoTime();
        return rowsRead -> {
            if (rowsRead > 0 && rowsRead % intervalRows == 0) {
                long elapsedMs = Math.max(0, (System.nanoTime() - started) / 1_000_000);
                long rowsPerSecond = elapsedMs == 0 ? rowsRead : rowsRead * 1_000 / elapsedMs;
                System.err.println("[IXIT GTFS Preprocessor] section="
                        + section
                        + " status=progress rows="
                        + rowsRead
                        + " elapsed_ms="
                        + elapsedMs
                        + " rows_per_second="
                        + rowsPerSecond
                        + " memory_used_mb="
                        + PerformanceTracker.usedMemoryMb());
            }
        };
    }

    private static GtfsCsvReader.ProgressListener heapGuardedProgressLogger(String section, long logIntervalRows) {
        return heapGuardedProgressLogger(section, logIntervalRows, streamingHeapGuardThresholdMb());
    }

    private static GtfsCsvReader.ProgressListener heapGuardedProgressLogger(String section, long logIntervalRows, long thresholdMb) {
        long started = System.nanoTime();
        return rowsRead -> {
            if (rowsRead % STREAMING_HEAP_GUARD_INTERVAL_ROWS == 0) {
                long beforeMb = PerformanceTracker.usedMemoryMb();
                if (beforeMb >= thresholdMb) {
                    System.gc();
                    long afterMb = PerformanceTracker.usedMemoryMb();
                    System.err.println("[IXIT GTFS Preprocessor] section=heap_guard phase="
                            + section
                            + " rows="
                            + rowsRead
                            + " threshold_mb="
                            + thresholdMb
                            + " memory_used_mb="
                            + beforeMb
                            + " after_gc_mb="
                            + afterMb);
                }
            }
            if (rowsRead % logIntervalRows == 0) {
                long elapsedMs = Math.max(1, (System.nanoTime() - started) / 1_000_000);
                long rowsPerSecond = rowsRead * 1000L / elapsedMs;
                System.err.println("[IXIT GTFS Preprocessor] section="
                        + section
                        + " status=progress rows="
                        + rowsRead
                        + " elapsed_ms="
                        + elapsedMs
                        + " rows_per_second="
                        + rowsPerSecond
                        + " memory_used_mb="
                        + PerformanceTracker.usedMemoryMb());
            }
        };
    }

    private static long streamingHeapGuardThresholdMb() {
        long configured = Long.getLong("ixit.gtfs.streamingHeapGuardMb", -1L);
        if (configured > 0) {
            return configured;
        }
        long maximumHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        return maximumHeapMb <= 2_700 ? 2_050 : 2_500;
    }

    private static long derivedHeapGuardThresholdMb() {
        long maximumHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        return Math.max(1, Math.min(streamingHeapGuardThresholdMb(), Math.min(2_050, maximumHeapMb * 7 / 10)));
    }

    private static RealFeedValidationReport buildRealFeedValidation(
            Path inputZip,
            Path outputDatabase,
            PerformanceReport performanceReport,
            SqliteContractReport contractReport,
            WarningSummary warningSummary,
            int totalWarnings,
            Map<String, String> indexSmokeChecks,
            RoutingCompatibilityAuditor.RoutingCompatibilityAuditReport routingAudit,
            SqliteDiagnosticsReport sqliteDiagnostics
    ) throws IOException {
        return new RealFeedValidationReport(
                SqliteContract.PREPROCESSOR_VERSION,
                inputZip,
                Instant.now(),
                Files.exists(outputDatabase) ? Files.size(outputDatabase) : 0,
                performanceReport,
                contractReport.rowCounts(),
                warningSummary.counts(),
                totalWarnings,
                warningSummary.criticalWarnings(),
                indexSmokeChecks,
                contractReport.contractVersion(),
                contractReport.preprocessorVersion(),
                routingAudit.warnCount() == 0 ? "PASS" : "WARN",
                sqliteDiagnostics
        );
    }

    private static void addStopQualityWarnings(List<Stop> stops, PreprocessReport.Builder report, WarningSummary warningSummary) {
        int missingNames = 0;
        int invalidCoordinates = 0;
        List<String> missingNameSamples = new java.util.ArrayList<>();
        List<String> invalidCoordinateSamples = new java.util.ArrayList<>();

        for (Stop stop : stops) {
            if (stop.stopName() == null || stop.stopName().isBlank()) {
                missingNames++;
                addSample(missingNameSamples, stop.stopId());
            }
            if (!hasValidCoordinates(stop)) {
                invalidCoordinates++;
                addSample(invalidCoordinateSamples, stop.stopId());
            }
        }

        if (missingNames > 0) {
            warningSummary.set("empty_stop_names", missingNames);
            report.warning("Stops with empty or missing stop_name: " + missingNames + ", samples: " + String.join(", ", missingNameSamples));
        }
        if (invalidCoordinates > 0) {
            warningSummary.set("invalid_coordinates", invalidCoordinates);
            report.warning("Stops without valid coordinates: " + invalidCoordinates + ", samples: " + String.join(", ", invalidCoordinateSamples));
        }
    }

    private static void addTripQualityWarnings(List<Trip> trips, List<Route> routes, PreprocessReport.Builder report, WarningSummary warningSummary) {
        Set<String> routeIds = routes.stream().map(Route::routeId).collect(Collectors.toUnmodifiableSet());
        Set<String> samples = new LinkedHashSet<>();
        int unknownRoutes = 0;
        for (Trip trip : trips) {
            if (!routeIds.contains(trip.routeId())) {
                unknownRoutes++;
                if (samples.size() < 5) {
                    samples.add(trip.tripId() + "->" + trip.routeId());
                }
            }
        }
        if (unknownRoutes > 0) {
            warningSummary.set("unknown_route_references", unknownRoutes);
            report.warning("Trips referencing unknown route_id: " + unknownRoutes + ", samples: " + String.join(", ", samples));
        }
    }

    private static void addStopAreaQualityWarnings(StopAreaReporter.StopAreaStats stats, PreprocessReport.Builder report, WarningSummary warningSummary) {
        if (stats.areasWithoutMembers() > 0) {
            report.warning("StopAreas without members: " + stats.areasWithoutMembers());
        }
        if (!stats.veryLargeStopAreas().isEmpty()) {
            warningSummary.set("stop_areas_too_large", stats.veryLargeStopAreas().size());
            report.warning("Very large StopAreas (threshold "
                    + StopAreaReporter.VERY_LARGE_STOP_AREA_THRESHOLD
                    + " stops): "
                    + stats.veryLargeStopAreas().stream()
                    .limit(5)
                    .map(StopAreaReporter.StopAreaSummary::toReportText)
                    .collect(Collectors.joining("; ")));
        }
    }

    private static void addSearchTokenQualityWarnings(StopSearchTokenBuilder.StreamingStats result, PreprocessReport.Builder report, WarningSummary warningSummary) {
        if (result.emptyTokenSourceCount() > 0) {
            report.warning("Names producing empty Search Tokens: "
                    + result.emptyTokenSourceCount()
                    + ", samples: "
                    + String.join(", ", result.emptyTokenSamples()));
        }
        if (result.duplicateTokenCount() > 0) {
            warningSummary.set("duplicate_tokens", result.duplicateTokenCount());
            report.warning("Duplicate Search Tokens skipped: " + result.duplicateTokenCount());
        }
    }

    private static void addHubProfileQualityWarnings(List<HubProfile> hubProfiles, PreprocessReport.Builder report) {
        List<String> suspicious = hubProfiles.stream()
                .filter(profile -> profile.stopCount() >= 10 && profile.routeCount() == 0)
                .limit(5)
                .map(profile -> profile.areaId() + "[stops=" + profile.stopCount() + "]")
                .toList();
        if (!suspicious.isEmpty()) {
            report.warning("HubProfiles with many stops but no routes: " + String.join(", ", suspicious));
        }
    }

    private static void addRouteAxisQualityWarnings(RouteAxisBuilder.RouteAxisStats stats, PreprocessReport.Builder report, WarningSummary warningSummary) {
        if (!stats.shortAxes().isEmpty()) {
            warningSummary.set("route_axes_too_short", stats.shortAxes().size());
            report.warning("RouteAxes with fewer than 2 StopAreas: "
                    + stats.shortAxes().stream()
                    .limit(5)
                    .map(axis -> axis.axisId() + "[route=" + axis.routeId() + ", stops=" + axis.stopCount() + "]")
                    .collect(Collectors.joining(", ")));
        }
        if (stats.tripsWithoutStopTimes() > 0) {
            warningSummary.set("trips_without_stop_times", stats.tripsWithoutStopTimes());
            report.warning("Trips without StopTimes: " + stats.tripsWithoutStopTimes());
        }
        if (stats.tripsWithoutUsableSequence() > 0) {
            report.warning("Trips without usable StopArea sequence: " + stats.tripsWithoutUsableSequence());
        }
        if (stats.unmappedStopTimeCount() > 0) {
            report.warning("StopTimes without StopArea mapping: "
                    + stats.unmappedStopTimeCount()
                    + ", samples: "
                    + String.join(", ", stats.unmappedStopSamples()));
        }
    }

    private static void addTransferRuleQualityWarnings(TransferRuleBuilder.TransferRuleStats stats, PreprocessReport.Builder report, WarningSummary warningSummary) {
        if (stats.gtfsTransfersUnmapped() > 0) {
            warningSummary.set("unmapped_transfers", stats.gtfsTransfersUnmapped());
            report.warning("GTFS transfers without StopArea mapping: "
                    + stats.gtfsTransfersUnmapped()
                    + ", samples: "
                    + String.join(", ", stats.unmappedTransferSamples()));
        }
        if (!stats.suspiciousSamples().isEmpty()) {
            for (String sample : stats.suspiciousSamples()) {
                if (sample.startsWith("negative_time:")) {
                    warningSummary.increment("transfer_rules_negative_time");
                } else if (sample.startsWith("very_long_time:")) {
                    warningSummary.increment("transfer_rules_very_long_time");
                }
            }
            report.warning("Suspicious TransferRules: " + String.join(", ", stats.suspiciousSamples()));
        }
    }

    private static <T> T measureIoWithProgress(PerformanceTracker performance, String name, String section, PerformanceTracker.ThrowingSupplier<T> supplier) throws IOException, SQLException {
        long started = logSectionStart(section);
        try {
            T result = measureIo(performance, name, supplier);
            logSectionEnd(section, started);
            return result;
        } catch (IOException | SQLException | RuntimeException ex) {
            logSectionFailed(section, started, ex);
            throw ex;
        }
    }

    private static <T> T measureSqlWithProgress(PerformanceTracker performance, String name, String section, PerformanceTracker.ThrowingSupplier<T> supplier) throws SQLException {
        long started = logSectionStart(section);
        try {
            T result = measureSql(performance, name, supplier);
            logSectionEnd(section, started);
            return result;
        } catch (SQLException | RuntimeException ex) {
            logSectionFailed(section, started, ex);
            throw ex;
        }
    }

    private static void measureSqlWithProgress(PerformanceTracker performance, String name, String section, PerformanceTracker.ThrowingRunnable runnable) throws SQLException {
        long started = logSectionStart(section);
        try {
            measureSql(performance, name, runnable);
            logSectionEnd(section, started);
        } catch (SQLException | RuntimeException ex) {
            logSectionFailed(section, started, ex);
            throw ex;
        }
    }

    private static <T> T measureIo(PerformanceTracker performance, String name, PerformanceTracker.ThrowingSupplier<T> supplier) throws IOException, SQLException {
        try {
            return performance.measure(name, supplier);
        } catch (IOException | SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static <T> T measureSql(PerformanceTracker performance, String name, PerformanceTracker.ThrowingSupplier<T> supplier) throws SQLException {
        try {
            return performance.measure(name, supplier);
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void measureSql(PerformanceTracker performance, String name, PerformanceTracker.ThrowingRunnable runnable) throws SQLException {
        try {
            performance.measure(name, runnable);
        } catch (SQLException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static boolean hasValidCoordinates(Stop stop) {
        return stop.stopLat() != null
                && stop.stopLon() != null
                && stop.stopLat() >= -90.0
                && stop.stopLat() <= 90.0
                && stop.stopLon() >= -180.0
                && stop.stopLon() <= 180.0;
    }

    private static void addSample(List<String> samples, String value) {
        if (samples.size() < 5) {
            samples.add(value);
        }
    }
}
