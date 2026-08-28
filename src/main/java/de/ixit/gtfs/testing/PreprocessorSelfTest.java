package de.ixit.gtfs.testing;

import de.ixit.gtfs.GtfsPreprocessor;
import de.ixit.gtfs.AuditArtifactCleaner;
import de.ixit.gtfs.BuildIdentity;
import de.ixit.gtfs.CanonicalStopAreaBuilder;
import de.ixit.gtfs.CityPrefixAliasResolver;
import de.ixit.gtfs.DisplayNameQualityBaselineBuilder;
import de.ixit.gtfs.DisplayNameAuditor;
import de.ixit.gtfs.DisplayNameTransformationRules;
import de.ixit.gtfs.AppReadySqliteValidator;
import de.ixit.gtfs.PreprocessOptions;
import de.ixit.gtfs.GtfsTimeParser;
import de.ixit.gtfs.HubProfileBuilder;
import de.ixit.gtfs.MobileArtifactManifest;
import de.ixit.gtfs.MobileArtifactPackager;
import de.ixit.gtfs.MobileArtifactUpdateContract;
import de.ixit.gtfs.MobileArtifactUpdateDescriptor;
import de.ixit.gtfs.PreprocessReport;
import de.ixit.gtfs.RealFeedDriftAuditor;
import de.ixit.gtfs.RoutingContractConsumerPoc;
import de.ixit.gtfs.RoutingContractRealFeedAuditor;
import de.ixit.gtfs.ServiceDayResolver;
import de.ixit.gtfs.ServiceDayModelAuditor;
import de.ixit.gtfs.ServiceDayRealFeedAuditor;
import de.ixit.gtfs.SoakCycleComparator;
import de.ixit.gtfs.SqliteContract;
import de.ixit.gtfs.SqliteContractValidator;
import de.ixit.gtfs.StopAreaBuilder;
import de.ixit.gtfs.StopAreaAliasBuilder;
import de.ixit.gtfs.StopAreaCityBuilder;
import de.ixit.gtfs.StopAreaNameHarmonizer;
import de.ixit.gtfs.StopAreaProfileBuilder;
import de.ixit.gtfs.StopAreaPublicDisplayNameFormatter;
import de.ixit.gtfs.StopAreaReporter;
import de.ixit.gtfs.StopNameNormalizer;
import de.ixit.gtfs.StopSearchTokenBuilder;
import de.ixit.gtfs.StopFootpathBuilder;
import de.ixit.gtfs.TransferFootpathAuditor;
import de.ixit.gtfs.SourceStationLinkBuilder;
import de.ixit.gtfs.RnvRouteColorEnricher;
import de.ixit.gtfs.VbbRouteColorEnricher;
import de.ixit.gtfs.model.Agency;
import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.CanonicalStopArea;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaAlias;
import de.ixit.gtfs.model.StopAreaCity;
import de.ixit.gtfs.model.StopAreaDisplayName;
import de.ixit.gtfs.model.StopAreaProfile;
import de.ixit.gtfs.model.StopSearchToken;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class PreprocessorSelfTest {
    private PreprocessorSelfTest() {
    }

    public static void main(String[] args) {
        GtfsFeedFusionSelfTest.run();
        buildIdentityIsDeterministicAndInputSensitive();
        normalizationKeepsSearchableStationHints();
        synonymsAreGenerated();
        tokensAreGeneratedForStopsAndAreas();
        stopAreaReportSummarizesMembership();
        stopFootpathsAreConcreteConservativeAndFailClosed();
        gtfsTimesStillSupportServiceDayOverflow();
        serviceDayResolverAppliesCalendarAndExceptions();
        serviceDayModelValidatesIanaTimezones();
        serviceDayRealFeedAuditComparesContractBaseline();
        soakCycleComparatorRejectsRegressions();
        realFeedDriftAuditRequiresNewHashesAndClassifiesDeltas();
        auditArtifactsRequirePassAndStayInsideToolBuild();
        stopAreaProfilesTrustRouteTypeOverRailLikeBusLabel();
        databaseBackedHubProfilesPreserveTransferReferenceCounts();
        canonicalStopAreasGroupStationFamilies();
        canonicalStopAreasMergeNearbyPrimaryCandidates();
        canonicalStopAreasKeepNearbyBusStopsSeparate();
        canonicalStopAreasKeepDistinctDistrictStationsSeparate();
        canonicalStopAreasUseCityAliasForTechnicalStationLevels();
        canonicalStopAreasPreferSpecificRailPlaceOverBareMunicipality();
        canonicalStopAreasPreferGenericCityRailStationOverSpecialAlias();
        canonicalStopAreasDoNotTreatDistrictStopsAsMunicipalityOnly();
        canonicalStopAreasDoNotInventBahnhofForUrbanStreetStops();
        publicStopAreaDisplayNamesUseStationCityPattern();
        publicStopAreaDisplayNamesStripMatchingCityPrefixes();
        genericStationSuffixRequiresSpecificDesignation();
        majorCityDisplayNamesRemainStable();
        displayNameTransformationsAreExplainable();
        displayQualityBaselineClassifiesWithoutDeleting();
        municipalityGeometryOverridesTechnicalCityGuessing();
        familyMembersKeepConcreteDisplayNames();
        stopAreaAliasesHandlePostposedCityNames();
        stopAreaAliasesUseMunicipalityCityAndStreetVariants();
        sqliteContractIsWrittenAndValidated();
        appRuntimeModeRequiresAppReadySqlite();
        mobileArtifactPackagingIsReducedSignedAndFailClosed();
        searchableMobileArtifactAndUpdateContractFailClosed();
        coreOnlyModeSkipsDerivedBuildersButKeepsContract();
        missingCalendarDatesFileStillProducesValidContract();
        sourceStationLinksOnlyUseVerifiedMatches();
        vbbRouteColorsAreScopedAndNeverOverwriteGtfsColors();
        rnvRouteColorsAreScopedAndNeverOverwriteGtfsColors();
        System.out.println("PreprocessorSelfTest passed");
    }

    private static void stopFootpathsAreConcreteConservativeAndFailClosed() {
        List<Stop> stops = List.of(
                new Stop("P", null, "Test station", 51.0, 7.0, null, 1, null),
                new Stop("A", null, "Platform A", 51.0, 7.0, "P", 0, "1"),
                new Stop("B", null, "Platform B", 51.0003, 7.0, "P", 0, "2"),
                new Stop("C", null, "Remote platform", 51.0, 7.015, "P", 0, "3")
        );
        List<de.ixit.gtfs.model.StopFootpath> footpaths = new java.util.ArrayList<>();
        StopFootpathBuilder.StopFootpathStats stats = new StopFootpathBuilder(stops).writeTo(footpaths::add);
        assertEquals(6L, stats.footpathCount());
        assertTrue(footpaths.stream().anyMatch(path -> path.fromStopId().equals("A")
                        && path.toStopId().equals("B")
                        && path.traversable()
                        && path.minTransferSeconds() >= 120),
                "Expected close concrete platforms to receive a conservative estimate");
        assertTrue(footpaths.stream().anyMatch(path -> path.fromStopId().equals("A")
                        && path.toStopId().equals("C")
                        && !path.traversable()
                        && "UNKNOWN".equals(path.quality())),
                "Expected an implausibly wide parent_station group to fail closed");
        assertEquals(1, stats.oversizedAreas());
        assertEquals(1, stats.extremeAreas());
    }

    private static void buildIdentityIsDeterministicAndInputSensitive() {
        try {
            Path directory = Files.createTempDirectory("ixit-build-identity-");
            Path input = directory.resolve("fixture.zip");
            Files.writeString(input, "fixture-a", StandardCharsets.UTF_8);

            BuildIdentity first = BuildIdentity.capture(input, PreprocessOptions.appRuntime());
            BuildIdentity repeated = BuildIdentity.capture(input, PreprocessOptions.appRuntime());
            assertEquals(first.buildIdentitySha256(), repeated.buildIdentitySha256());
            assertTrue(first.sourceGtfsSha256().matches("[0-9a-f]{64}"), "Expected source SHA-256");
            assertTrue(first.preprocessorArtifactSha256().matches("[0-9a-f]{64}"), "Expected artifact SHA-256");
            assertEquals("APP_RUNTIME", first.runMode());
            assertEquals(SqliteContract.CONTRACT_VERSION, first.contractVersion());
            assertEquals(BuildIdentity.NOT_PROVIDED, first.municipalityDataSha256());

            Files.writeString(input, "fixture-b", StandardCharsets.UTF_8);
            BuildIdentity changed = BuildIdentity.capture(input, PreprocessOptions.appRuntime());
            assertTrue(
                    !first.buildIdentitySha256().equals(changed.buildIdentitySha256()),
                    "Expected changed GTFS input to change build identity"
            );
        } catch (IOException exception) {
            throw new AssertionError("Build identity fixture must be readable", exception);
        }
    }

    private static void auditArtifactsRequirePassAndStayInsideToolBuild() {
        try {
            Path toolRoot = Files.createTempDirectory("ixit-artifact-cleanup-");
            Path auditRoot = toolRoot.resolve(Path.of("build", "v0.7.4"));
            Path output = auditRoot.resolve("output");
            Path inputs = auditRoot.resolve(Path.of("local-data", "from-routing-cache"));
            Files.createDirectories(output);
            Files.createDirectories(inputs);
            Files.writeString(output.resolve("status.txt"), "PASS fixture\n", StandardCharsets.UTF_8);
            Files.writeString(
                    output.resolve("service-day-real-feed-audit-v0.7.4.json"),
                    "{\"pass\":true}",
                    StandardCharsets.UTF_8
            );
            Path sqlite = output.resolve("candidate.sqlite");
            Path fusedZip = output.resolve("candidate.zip");
            Path inputCopy = inputs.resolve("de.zip");
            Path evidence = output.resolve("preprocess.log");
            Files.writeString(sqlite, "sqlite", StandardCharsets.UTF_8);
            Files.writeString(fusedZip, "fused", StandardCharsets.UTF_8);
            Files.writeString(inputCopy, "input", StandardCharsets.UTF_8);
            Files.writeString(evidence, "evidence", StandardCharsets.UTF_8);

            var dryRun = AuditArtifactCleaner.clean(toolRoot, auditRoot, false, true);
            assertEquals(3, dryRun.candidateCount());
            assertEquals(0, dryRun.deletedCount());
            assertTrue(Files.exists(sqlite), "Dry-run must retain SQLite candidate");
            assertTrue(Files.exists(fusedZip), "Dry-run must retain fused ZIP");
            assertTrue(Files.exists(inputCopy), "Dry-run must retain copied feed input");

            var executed = AuditArtifactCleaner.clean(toolRoot, auditRoot, true, true);
            assertTrue(executed.pass(), "Expected successful artifact cleanup");
            assertEquals(3, executed.deletedCount());
            assertEquals(false, Files.exists(sqlite));
            assertEquals(false, Files.exists(fusedZip));
            assertEquals(false, Files.exists(inputCopy));
            assertTrue(Files.exists(evidence), "Cleanup must retain audit evidence");
            assertTrue(
                    Files.exists(output.resolve(AuditArtifactCleaner.REPORT_NAME)),
                    "Cleanup must retain its machine-readable report"
            );

            Path outside = Files.createTempDirectory("ixit-artifact-cleanup-outside-");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AuditArtifactCleaner.clean(toolRoot, outside, false, false)
            );

            Path failedAudit = toolRoot.resolve(Path.of("build", "failed-audit"));
            Path failedOutput = failedAudit.resolve("output");
            Files.createDirectories(failedOutput);
            Path protectedCandidate = failedOutput.resolve("must-remain.sqlite");
            Files.writeString(protectedCandidate, "protected", StandardCharsets.UTF_8);
            Files.writeString(failedOutput.resolve("status.txt"), "FAILED fixture\n", StandardCharsets.UTF_8);
            Files.writeString(
                    failedOutput.resolve("service-day-real-feed-audit-v0.7.4.json"),
                    "{\"pass\":false}",
                    StandardCharsets.UTF_8
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AuditArtifactCleaner.clean(toolRoot, failedAudit, true, false)
            );
            assertTrue(Files.exists(protectedCandidate), "Failed audit cleanup must retain its SQLite candidate");

            Path driftAudit = toolRoot.resolve(Path.of("build", "approved-drift-audit"));
            Path driftOutput = driftAudit.resolve("output");
            Files.createDirectories(driftOutput);
            Path driftCandidate = driftOutput.resolve("approved-drift.sqlite");
            Files.writeString(driftCandidate, "approved", StandardCharsets.UTF_8);
            Files.writeString(driftOutput.resolve("status.txt"), "PASS drift fixture\n", StandardCharsets.UTF_8);
            Files.writeString(
                    driftOutput.resolve("service-day-real-feed-audit-v0.7.4.json"),
                    "{\"pass\":false}",
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    driftOutput.resolve("real-feed-drift-audit-v0.7.4.json"),
                    "{\"pass\":true,\"baselinePromotionState\":\"ELIGIBLE_FOR_MANUAL_REVIEW\","
                            + "\"candidateAuditCompatibility\":\"ROW_COUNT_DRIFT_ONLY\",\"failures\":[]}",
                    StandardCharsets.UTF_8
            );
            AuditArtifactCleaner.clean(toolRoot, driftAudit, true, false);
            assertEquals(false, Files.exists(driftCandidate));

            Path rejectedDriftAudit = toolRoot.resolve(Path.of("build", "rejected-drift-audit"));
            Path rejectedDriftOutput = rejectedDriftAudit.resolve("output");
            Files.createDirectories(rejectedDriftOutput);
            Path rejectedDriftCandidate = rejectedDriftOutput.resolve("rejected-drift.sqlite");
            Files.writeString(rejectedDriftCandidate, "protected", StandardCharsets.UTF_8);
            Files.writeString(rejectedDriftOutput.resolve("status.txt"), "PASS drift fixture\n", StandardCharsets.UTF_8);
            Files.writeString(
                    rejectedDriftOutput.resolve("service-day-real-feed-audit-v0.7.4.json"),
                    "{\"pass\":false}",
                    StandardCharsets.UTF_8
            );
            Files.writeString(
                    rejectedDriftOutput.resolve("real-feed-drift-audit-v0.7.4.json"),
                    "{\"pass\":false,\"baselinePromotionState\":\"BLOCKED\","
                            + "\"candidateAuditCompatibility\":\"ROW_COUNT_DRIFT_ONLY\","
                            + "\"failures\":[\"HEAP_HEADROOM\"]}",
                    StandardCharsets.UTF_8
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> AuditArtifactCleaner.clean(toolRoot, rejectedDriftAudit, true, false)
            );
            assertTrue(Files.exists(rejectedDriftCandidate), "Rejected drift cleanup must retain its SQLite candidate");
        } catch (IOException ex) {
            throw new AssertionError("Artifact cleanup self-test failed", ex);
        }
    }

    private static void soakCycleComparatorRejectsRegressions() {
        try {
            Path directory = Files.createTempDirectory("ixit-soak-comparison-");
            Path baseline = directory.resolve("baseline.json");
            Path candidate = directory.resolve("candidate.json");
            Path audit = directory.resolve("audit.json");
            Path preprocessLog = directory.resolve("preprocess.log");
            Files.writeString(baseline, soakContractFixture("0.7.2", 9, 2400, 0), StandardCharsets.UTF_8);
            Files.writeString(candidate, soakContractFixture("0.7.4", 9, 2200, 0), StandardCharsets.UTF_8);
            Files.writeString(audit, "{\"pass\":true}", StandardCharsets.UTF_8);

            var passing = SoakCycleComparator.compare(
                    baseline,
                    candidate,
                    audit,
                    "cycle-01",
                    2300,
                    Map.of("DE_FULL", "a".repeat(64), "CH", "b".repeat(64))
            );
            assertTrue(passing.pass(), "Expected identical soak fixture to pass: " + passing.failures());
            assertEquals(2200L, passing.maximumReportedUsedHeapMb());

            Files.writeString(candidate, soakContractFixture("0.7.4", 10, 2200, 1), StandardCharsets.UTF_8);
            Files.writeString(
                    preprocessLog,
                    "[IXIT GTFS Preprocessor] section=stop_times status=progress memory_used_mb=2350\n",
                    StandardCharsets.UTF_8
            );
            var regression = SoakCycleComparator.compare(
                    baseline,
                    candidate,
                    audit,
                    preprocessLog,
                    "cycle-02",
                    2300,
                    Map.of()
            );
            assertEquals(false, regression.pass());
            assertTrue(
                    regression.failures().stream().anyMatch(failure -> failure.startsWith("row_count_stop_times")),
                    "Expected row-count regression failure"
            );
            assertTrue(
                    regression.failures().stream().anyMatch(failure -> failure.startsWith("display_quality_destructive_action_count")),
                    "Expected destructive display action failure"
            );
            assertTrue(
                    regression.failures().stream().anyMatch(failure -> failure.startsWith("maximum_reported_used_heap_mb")),
                    "Expected used-heap headroom failure"
            );
        } catch (IOException ex) {
            throw new AssertionError("Soak comparison self-test failed", ex);
        }
    }

    private static void realFeedDriftAuditRequiresNewHashesAndClassifiesDeltas() {
        try {
            Path directory = Files.createTempDirectory("ixit-feed-drift-");
            Path baseline = directory.resolve("baseline.json");
            Path candidate = directory.resolve("candidate.json");
            Path audit = directory.resolve("audit.json");
            Files.writeString(baseline, soakContractFixture("0.7.3", 9, 2200, 0), StandardCharsets.UTF_8);
            Files.writeString(candidate, soakContractFixture("0.7.4", 10, 2200, 0), StandardCharsets.UTF_8);
            Files.writeString(audit, """
                    {
                      "pass": false,
                      "failures": ["row-count regression for stop_times: delta=1"],
                      "performanceEvidence": {"maximum_reported_used_heap_mb": 2200}
                    }
                    """, StandardCharsets.UTF_8);

            var expectedDrift = RealFeedDriftAuditor.audit(
                    baseline,
                    candidate,
                    audit,
                    Map.of("DE_FULL", "a".repeat(64), "CH", "b".repeat(64)),
                    Map.of("DE_FULL", "c".repeat(64), "CH", "b".repeat(64))
            );
            assertTrue(expectedDrift.pass(), "Expected bounded new-feed drift to pass: " + expectedDrift.failures());
            assertEquals("ELIGIBLE_FOR_MANUAL_REVIEW", expectedDrift.baselinePromotionState());
            assertEquals("ROW_COUNT_DRIFT_ONLY", expectedDrift.candidateAuditCompatibility());
            assertEquals(2200L, expectedDrift.maximumReportedUsedHeapMb());
            assertTrue(
                    expectedDrift.metrics().stream().anyMatch(metric -> "stop_times".equals(metric.metric())
                            && "EXPECTED_FEED_DRIFT".equals(metric.classification())),
                    "Expected changed stop_times to be classified as feed drift"
            );

            String excessiveChange = soakContractFixture("0.7.4", 9, 2200, 0)
                    .replace("\"stops\": 3", "\"stops\": 30");
            Files.writeString(candidate, excessiveChange, StandardCharsets.UTF_8);
            var blocked = RealFeedDriftAuditor.audit(
                    baseline,
                    candidate,
                    audit,
                    Map.of("DE_FULL", "a".repeat(64)),
                    Map.of("DE_FULL", "c".repeat(64))
            );
            assertEquals(false, blocked.pass());
            assertTrue(
                    blocked.failures().stream().anyMatch(failure -> failure.startsWith("ROW_COUNT.stops changed")),
                    "Expected excessive structural drift to require review"
            );

            var unchangedSources = RealFeedDriftAuditor.audit(
                    baseline,
                    candidate,
                    audit,
                    Map.of("DE_FULL", "a".repeat(64)),
                    Map.of("DE_FULL", "a".repeat(64))
            );
            assertTrue(
                    unchangedSources.failures().stream().anyMatch(failure -> failure.startsWith("NO_NEW_FEED_REVISION")),
                    "Expected unchanged source hashes to block a drift audit"
            );

            Files.writeString(audit, """
                    {
                      "pass": true,
                      "failures": [],
                      "performanceEvidence": {"maximum_reported_used_heap_mb": 2393}
                    }
                    """, StandardCharsets.UTF_8);
            var heapBlocked = RealFeedDriftAuditor.audit(
                    baseline,
                    candidate,
                    audit,
                    Map.of("DE_FULL", "a".repeat(64)),
                    Map.of("DE_FULL", "c".repeat(64))
            );
            assertEquals(false, heapBlocked.pass());
            assertTrue(
                    heapBlocked.failures().stream().anyMatch(failure -> failure.startsWith("HEAP_HEADROOM")),
                    "Expected excessive candidate heap to block baseline promotion"
            );
        } catch (IOException exception) {
            throw new AssertionError("Real-feed drift audit self-test failed", exception);
        }
    }

    private static String soakContractFixture(
            String preprocessorVersion,
            long stopTimeCount,
            long maximumHeapMb,
            long destructiveActionCount
    ) {
        return """
                {
                  "preprocessor_version": "%s",
                  "contract_version": "0.7",
                  "row_counts": {
                    "stops": 3,
                    "stop_areas": 2,
                    "stop_area_members": 3,
                    "routes": 2,
                    "trips": 4,
                    "stop_times": %d,
                    "transfers": 2,
                    "calendar": 2,
                    "calendar_dates": 2,
                    "feed_agencies": 1,
                    "service_calendar_summary": 2,
                    "stop_search_tokens": 10,
                    "stop_area_display_names": 2,
                    "display_name_quality_findings": 2
                  },
                  "service_day_model": {
                    "pass": true,
                    "services": 2,
                    "trip_services": 1,
                    "base_calendar_services": 2,
                    "exception_services": 1,
                    "exception_only_services": 0,
                    "unresolved_trip_services": 0,
                    "invalid_iana_timezone_services": 0,
                    "unknown_timezone_trip_services": 0,
                    "multiple_timezone_trip_services": 0,
                    "overflow_stop_times": 3,
                    "maximum_service_day_seconds": 90630
                  },
                  "routing_compatibility_audit": {"warn": 0},
                  "app_ready_sqlite": {
                    "app_ready": true,
                    "display_name_audit": {"pass": true},
                    "display_name_quality_baseline": {
                      "pass": true,
                      "finding_count": 2,
                      "prefix_finding_count": 1,
                      "municipality_only_finding_count": 1,
                      "coverage_gap_count": 0,
                      "destructive_action_count": %d
                    }
                  },
                  "real_feed_validation": {
                    "memory_snapshots_mb": {"after_sqlite_indexes_mb": %d}
                  }
                }
                """.formatted(preprocessorVersion, stopTimeCount, destructiveActionCount, maximumHeapMb);
    }

    private static void serviceDayResolverAppliesCalendarAndExceptions() {
        try {
            Path directory = Files.createTempDirectory("ixit-service-day-");
            Path database = directory.resolve("service-day.sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE service_calendar_summary (
                            service_id TEXT PRIMARY KEY,
                            has_calendar INTEGER NOT NULL,
                            weekday_mask INTEGER NOT NULL,
                            start_date TEXT,
                            end_date TEXT,
                            trip_count INTEGER NOT NULL,
                            service_timezone TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        CREATE TABLE calendar_dates (
                            service_id TEXT NOT NULL,
                            date TEXT NOT NULL,
                            exception_type INTEGER,
                            PRIMARY KEY(service_id, date)
                        )
                        """);
                statement.execute("""
                        INSERT INTO service_calendar_summary VALUES
                            ('WEEKDAY', 1, 31, '20260101', '20261231', 3, 'Europe/Berlin'),
                            ('EX_ONLY', 0, 0, NULL, NULL, 1, 'Europe/Berlin')
                        """);
                statement.execute("""
                        INSERT INTO calendar_dates VALUES
                            ('WEEKDAY', '20260102', 2),
                            ('EX_ONLY', '20260103', 1)
                        """);

                var weekdayActive = ServiceDayResolver.resolve(connection, "WEEKDAY", LocalDate.of(2026, 1, 5));
                assertTrue(weekdayActive.active(), "Expected Monday base-calendar service");
                assertEquals("CALENDAR_WEEKDAY_ACTIVE", weekdayActive.reason());
                assertEquals(3L, weekdayActive.activeTripCount());
                assertEquals("Europe/Berlin", weekdayActive.serviceTimezone());

                var removed = ServiceDayResolver.resolve(connection, "WEEKDAY", LocalDate.of(2026, 1, 2));
                assertEquals(false, removed.active());
                assertEquals("CALENDAR_DATES_REMOVAL", removed.reason());
                assertEquals(0L, removed.activeTripCount());

                var added = ServiceDayResolver.resolve(connection, "EX_ONLY", LocalDate.of(2026, 1, 3));
                assertTrue(added.active(), "Expected calendar_dates addition to activate exception-only service");
                assertEquals("CALENDAR_DATES_ADDITION", added.reason());
                assertEquals(1L, added.activeTripCount());

                var absentException = ServiceDayResolver.resolve(connection, "EX_ONLY", LocalDate.of(2026, 1, 4));
                assertEquals(false, absentException.active());
                assertEquals("EXCEPTIONS_ONLY_NO_ADDITION", absentException.reason());
            }
        } catch (IOException | SQLException ex) {
            throw new AssertionError("Service-day resolver self-test failed", ex);
        }
    }

    private static void serviceDayModelValidatesIanaTimezones() {
        try {
            Path directory = Files.createTempDirectory("ixit-service-day-timezone-");
            Path database = directory.resolve("service-day-timezone.sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE calendar (
                            service_id TEXT PRIMARY KEY,
                            monday INTEGER, tuesday INTEGER, wednesday INTEGER, thursday INTEGER,
                            friday INTEGER, saturday INTEGER, sunday INTEGER,
                            start_date TEXT, end_date TEXT
                        )
                        """);
                statement.execute("""
                        CREATE TABLE calendar_dates (
                            service_id TEXT, date TEXT, exception_type INTEGER, exception_action TEXT
                        )
                        """);
                statement.execute("CREATE TABLE stop_times (arrival_seconds INTEGER, departure_seconds INTEGER)");
                statement.execute("""
                        CREATE TABLE service_calendar_summary (
                            service_id TEXT PRIMARY KEY,
                            has_calendar INTEGER NOT NULL,
                            weekday_mask INTEGER NOT NULL,
                            start_date TEXT,
                            end_date TEXT,
                            addition_count INTEGER NOT NULL,
                            removal_count INTEGER NOT NULL,
                            trip_count INTEGER NOT NULL,
                            service_timezone TEXT NOT NULL,
                            status TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        INSERT INTO calendar VALUES
                            ('VALID', 1, 1, 1, 1, 1, 0, 0, '20260101', '20261231')
                        """);
                statement.execute("INSERT INTO stop_times VALUES (88200, 90600)");
                statement.execute("""
                        INSERT INTO service_calendar_summary VALUES
                            ('VALID', 1, 31, '20260101', '20261231', 0, 0, 2, 'Europe/Berlin', 'BASE_ONLY'),
                            ('INVALID', 0, 0, NULL, NULL, 1, 0, 1, 'Mars/Olympus', 'EXCEPTIONS_ONLY'),
                            ('UNKNOWN_ZONE', 0, 0, NULL, NULL, 1, 0, 1, 'UNKNOWN', 'EXCEPTIONS_ONLY'),
                            ('MULTIPLE_ZONE', 0, 0, NULL, NULL, 1, 0, 1, 'MULTIPLE', 'EXCEPTIONS_ONLY')
                        """);

                var invalid = ServiceDayModelAuditor.audit(connection);
                assertEquals(1L, invalid.invalidIanaTimezoneServiceCount());
                assertEquals(1L, invalid.unknownTimezoneTripServiceCount());
                assertEquals(1L, invalid.multipleTimezoneTripServiceCount());
                assertTrue(!invalid.pass(), "Expected invalid timezone model to fail");

                statement.execute("UPDATE service_calendar_summary SET service_timezone = 'Europe/Zurich' WHERE service_id = 'INVALID'");
                statement.execute("UPDATE service_calendar_summary SET trip_count = 0 WHERE service_id IN ('UNKNOWN_ZONE', 'MULTIPLE_ZONE')");
                var valid = ServiceDayModelAuditor.audit(connection);
                assertEquals(0L, valid.invalidIanaTimezoneServiceCount());
                assertEquals(0L, valid.unknownTimezoneTripServiceCount());
                assertEquals(0L, valid.multipleTimezoneTripServiceCount());
                assertTrue(valid.pass(), "Expected valid IANA timezone model to pass");
            }
        } catch (IOException | SQLException exception) {
            throw new AssertionError("Service-day timezone audit self-test failed", exception);
        }
    }

    private static void serviceDayRealFeedAuditComparesContractBaseline() {
        try {
            Path directory = Files.createTempDirectory("ixit-service-day-real-feed-audit-");
            Path current = directory.resolve("current.sqlite");
            Path baseline = directory.resolve("baseline.sqlite");
            createServiceDayAuditFixture(current, SqliteContract.CONTRACT_VERSION, SqliteContract.PREPROCESSOR_VERSION, true);
            createServiceDayAuditFixture(baseline, "0.6", "0.6.4", false);
            Path preprocessReport = directory.resolve("preprocess-report.json");
            Files.writeString(preprocessReport, """
                    {
                      "real_feed_validation": {
                        "total_runtime_ms": 1234,
                        "memory_snapshots_mb": {"after_stop_times": 321, "complete": 123}
                      },
                      "sqlite_diagnostics": {
                        "stop_times_rows": 1,
                        "stop_times_write_ms": 10,
                        "stop_times_rows_per_second": 100,
                        "stop_times_max_commit_ms": 5
                      }
                    }
                    """, StandardCharsets.UTF_8);

            var report = ServiceDayRealFeedAuditor.audit(
                    current,
                    baseline,
                    preprocessReport,
                    "unchanged copies from active routing feed cache",
                    Map.of("DE_FULL", "a".repeat(64), "CH", "b".repeat(64)),
                    List.of(LocalDate.of(2026, 8, 8))
            );
            assertTrue(report.pass(), "Expected service-day real-feed audit fixture to pass: " + report.failures());
            assertEquals(0L, report.baselineComparison().rowCountDeltas().get("stop_times"));
            assertEquals(321L, report.performanceEvidence().get("maximum_reported_used_heap_mb"));
            assertEquals(1L, report.serviceDayModel().overflowStopTimeCount());
            assertEquals(1L, report.exceptionStatistics().additionRows());
            assertEquals(1L, report.serviceDateSpotchecks().getFirst().activeServiceCount());
            assertEquals(2L, report.serviceDateSpotchecks().getFirst().activeTripCount());
        } catch (IOException | SQLException exception) {
            throw new AssertionError("Service-day real-feed comparison self-test failed", exception);
        }
    }

    private static void createServiceDayAuditFixture(
            Path database,
            String contractVersion,
            String preprocessorVersion,
            boolean current
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE ixit_metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("INSERT INTO ixit_metadata VALUES ('contract_version', '" + contractVersion + "')");
            statement.execute("INSERT INTO ixit_metadata VALUES ('preprocessor_version', '" + preprocessorVersion + "')");
            for (String table : List.of(
                    "stops", "stop_areas", "stop_area_members", "routes", "trips", "transfers",
                    "stop_search_tokens", "stop_area_display_names", "feed_agencies"
            )) {
                statement.execute("CREATE TABLE " + table + " (id INTEGER)");
            }
            statement.execute("CREATE TABLE stop_times (arrival_seconds INTEGER, departure_seconds INTEGER)");
            statement.execute("INSERT INTO stop_times VALUES (88200, 90600)");
            statement.execute("""
                    CREATE TABLE calendar (
                        service_id TEXT PRIMARY KEY,
                        monday INTEGER, tuesday INTEGER, wednesday INTEGER, thursday INTEGER,
                        friday INTEGER, saturday INTEGER, sunday INTEGER,
                        start_date TEXT, end_date TEXT
                    )
                    """);
            statement.execute("""
                    INSERT INTO calendar VALUES
                        ('WEEKEND', 0, 0, 0, 0, 0, 1, 1, '20260101', '20261231')
                    """);
            if (current) {
                statement.execute("""
                        CREATE TABLE calendar_dates (
                            service_id TEXT, date TEXT, exception_type INTEGER, exception_action TEXT
                        )
                        """);
                statement.execute("INSERT INTO calendar_dates VALUES ('WEEKEND', '20260808', 1, 'ADDITION')");
                statement.execute("""
                        CREATE TABLE service_calendar_summary (
                            service_id TEXT PRIMARY KEY,
                            has_calendar INTEGER NOT NULL,
                            weekday_mask INTEGER NOT NULL,
                            start_date TEXT,
                            end_date TEXT,
                            addition_count INTEGER NOT NULL,
                            removal_count INTEGER NOT NULL,
                            trip_count INTEGER NOT NULL,
                            service_timezone TEXT NOT NULL,
                            status TEXT NOT NULL
                        )
                        """);
                statement.execute("""
                        INSERT INTO service_calendar_summary VALUES
                            ('WEEKEND', 1, 96, '20260101', '20261231', 1, 0, 2, 'Europe/Berlin', 'BASE_WITH_EXCEPTIONS')
                        """);
            } else {
                statement.execute("CREATE TABLE calendar_dates (id INTEGER)");
                statement.execute("INSERT INTO calendar_dates VALUES (1)");
            }
        }
    }

    private static void municipalityGeometryOverridesTechnicalCityGuessing() {
        try {
            Path directory = Files.createTempDirectory("ixit-municipality-fixture-");
            Path geoJson = directory.resolve("municipalities.geojson");
            Files.writeString(geoJson, """
                    {
                      "type": "FeatureCollection",
                      "features": [
                        {
                          "type": "Feature",
                          "properties": {
                            "ags": "05913000",
                            "gen": "Dortmund",
                            "bez": "Stadt"
                          },
                          "geometry": {
                            "type": "Polygon",
                            "coordinates": [[[7.2,51.3],[7.7,51.3],[7.7,51.7],[7.2,51.7],[7.2,51.3]]]
                          }
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            List<StopArea> areas = List.of(
                    new StopArea("UNI", "Universitaet", 51.50, 7.45, 1),
                    new StopArea("HOERDE", "Dortmund Hoerde Bahnhof", 51.48, 7.50, 1),
                    new StopArea("NO_COORD", "Universitaet", null, null, 1),
                    new StopArea("FALLBACK", "Essen Hbf", 50.0, 8.0, 1)
            );
            StopAreaCityBuilder.StopAreaCityBuildResult result = StopAreaCityBuilder.build(
                    areas,
                    geoJson,
                    "BKG_VG250_2025-01-01"
            );
            Map<String, StopAreaCity> cities = result.cities().stream()
                    .collect(Collectors.toMap(StopAreaCity::areaId, Function.identity()));

            assertEquals("Dortmund", cities.get("UNI").cityName());
            assertEquals("OFFICIAL_BOUNDARY", cities.get("UNI").quality());
            assertEquals("", cities.get("NO_COORD").cityName());
            assertEquals("UNRESOLVED", cities.get("NO_COORD").quality());
            assertEquals("Essen", cities.get("FALLBACK").cityName());
            assertEquals("INFERRED", cities.get("FALLBACK").quality());

            StopAreaDisplayName university = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                    areas.get(0),
                    cities.get("UNI")
            );
            StopAreaDisplayName districtStation = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                    areas.get(1),
                    cities.get("HOERDE")
            );
            assertEquals("Universitaet, Dortmund", university.publicDisplayName());
            assertEquals("Hoerde, Dortmund", districtStation.publicDisplayName());
            assertTrue(university.source().contains("BKG_VG250_GEOMETRY"), "Expected official city source");

            StopAreaDisplayName lindau = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                    new StopArea("LINDAU", "Lindau, Reutin Bahnhof", 47.552843, 9.7040852, 1),
                    new StopAreaCity(
                            "LINDAU",
                            "09776116",
                            "Lindau (Bodensee)",
                            "Stadt",
                            "BKG_VG250_GEOMETRY",
                            "OFFICIAL_BOUNDARY",
                            "2025-01-01",
                            "fixture"
                    )
            );
            assertEquals("Reutin, Lindau", lindau.publicDisplayName());
            assertEquals("Lindau", lindau.publicCityName());
        } catch (IOException exception) {
            throw new AssertionError("Municipality fixture must be readable", exception);
        }
    }

    private static void vbbRouteColorsAreScopedAndNeverOverwriteGtfsColors() {
        try {
            VbbRouteColorEnricher.Result result = VbbRouteColorEnricher.enrich(
                    List.of(
                            new Route("berlin-u1", "bvg", "U1", "", 1, "", ""),
                            new Route("munich-u1", "mvg", "U1", "", 1, "", ""),
                            new Route("berlin-re1", "odeg", "RE1", "", 2, "", ""),
                            new Route("custom-re1", "odeg", "RE1", "", 2, "123456", "ABCDEF")
                    ),
                    List.of(
                            new Agency("bvg", "Berliner Verkehrsbetriebe"),
                            new Agency("mvg", "Stadtwerke München"),
                            new Agency("odeg", "Ostdeutsche Eisenbahn GmbH")
                    )
            );
            assertEquals(2, result.appliedCount());
            assertEquals("7DAD4C", result.routes().get(0).routeColor());
            assertEquals("", result.routes().get(1).routeColor());
            assertEquals("E2001A", result.routes().get(2).routeColor());
            assertEquals("123456", result.routes().get(3).routeColor());
            assertEquals("ABCDEF", result.routes().get(3).routeTextColor());
        } catch (IOException exception) {
            throw new AssertionError("VBB route color catalog must be readable", exception);
        }
    }

    private static void rnvRouteColorsAreScopedAndNeverOverwriteGtfsColors() {
        try {
            RnvRouteColorEnricher.Result result = RnvRouteColorEnricher.enrich(
                    List.of(
                            new Route("rnv-1", "rnv", "1", "", 0, "", ""),
                            new Route("other-1", "other", "1", "", 3, "", ""),
                            new Route("rnv-5", "rnv", "5", "", 0, "123456", "ABCDEF")
                    ),
                    List.of(
                            new Agency("rnv", "Rhein-Neckar-Verkehr GmbH"),
                            new Agency("other", "Stadtwerke Beispielstadt")
                    )
            );
            assertEquals(1, result.appliedCount());
            assertEquals("F39B9B", result.routes().get(0).routeColor());
            assertEquals("FFFFFF", result.routes().get(0).routeTextColor());
            assertEquals("", result.routes().get(1).routeColor());
            assertEquals("123456", result.routes().get(2).routeColor());
            assertEquals("ABCDEF", result.routes().get(2).routeTextColor());
        } catch (IOException exception) {
            throw new AssertionError("RNV route color catalog must be readable", exception);
        }
    }

    private static void sourceStationLinksOnlyUseVerifiedMatches() {
        try {
            Path directory = Files.createTempDirectory("ixit-source-station-links-");
            Path source = directory.resolve("source.sqlite");
            Path runtime = directory.resolve("runtime.sqlite");
            Path output = directory.resolve("links.sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stops(stop_id TEXT PRIMARY KEY, stop_code TEXT, stop_name TEXT, stop_lat REAL, stop_lon REAL, parent_station TEXT)");
                statement.execute("INSERT INTO stops VALUES ('de:global', NULL, 'Global', 51.0, 7.0, NULL)");
                statement.execute("INSERT INTO stops VALUES ('source-code', 'CODE', 'Code', 51.1, 7.1, NULL)");
                statement.execute("INSERT INTO stops VALUES ('source-coordinate', NULL, 'Coordinate', 51.2, 7.2, NULL)");
                statement.execute("INSERT INTO stops VALUES ('source-coordinate-unique', NULL, 'Different source label', 51.4, 7.4, NULL)");
                statement.execute("INSERT INTO stops VALUES ('source-ambiguous', 'AMB', 'Ambiguous', 51.3, 7.3, NULL)");
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + runtime); Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stops(stop_id TEXT PRIMARY KEY, stop_code TEXT, stop_name_normalized TEXT, stop_lat REAL, stop_lon REAL)");
                statement.execute("CREATE TABLE stop_area_members(stop_id TEXT, area_id TEXT)");
                statement.execute("CREATE TABLE canonical_stop_area_members(canonical_area_id TEXT, area_id TEXT)");
                statement.execute("CREATE TABLE canonical_stop_areas(canonical_area_id TEXT PRIMARY KEY, primary_stop_area_id TEXT)");
                statement.execute("INSERT INTO stops VALUES ('de:global', NULL, 'global', 51.0, 7.0)");
                statement.execute("INSERT INTO stops VALUES ('runtime-code', 'CODE', 'code', 51.1, 7.1)");
                statement.execute("INSERT INTO stops VALUES ('runtime-coordinate', NULL, 'coordinate', 51.2001, 7.2001)");
                statement.execute("INSERT INTO stops VALUES ('runtime-coordinate-unique', NULL, 'different runtime label', 51.40005, 7.40005)");
                statement.execute("INSERT INTO stops VALUES ('runtime-ambiguous-a', 'AMB', 'ambiguous', 51.3, 7.3)");
                statement.execute("INSERT INTO stops VALUES ('runtime-ambiguous-b', 'AMB', 'ambiguous', 51.3, 7.3)");
                for (String value : List.of("de:global|A", "runtime-code|B", "runtime-coordinate|C", "runtime-coordinate-unique|F", "runtime-ambiguous-a|D", "runtime-ambiguous-b|E")) {
                    String[] parts = value.split("\\|");
                    statement.execute("INSERT INTO stop_area_members VALUES ('" + parts[0] + "', 'area-" + parts[1] + "')");
                    statement.execute("INSERT INTO canonical_stop_area_members VALUES ('CAN-" + parts[1] + "', 'area-" + parts[1] + "')");
                    statement.execute("INSERT INTO canonical_stop_areas VALUES ('CAN-" + parts[1] + "', 'area-" + parts[1] + "')");
                }
            }
            SourceStationLinkBuilder.BuildResult result = new SourceStationLinkBuilder().build(source, runtime, "VBB", output, "fixture-published");
            assertEquals(5, result.sourceStationCount());
            assertEquals(1, result.exactGlobalIdCount());
            assertEquals(1, result.exactStopCodeCount());
            assertEquals(1, result.coordinateNameCheckedCount());
            assertEquals(1, result.coordinateUniqueCount());
            assertEquals(1, result.ambiguousOrUnmatchedCount());
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + output); Statement statement = connection.createStatement()) {
                assertEquals(4, queryInt(connection, "SELECT COUNT(*) FROM source_station_links"));
                assertEquals("EXACT_GLOBAL_ID", queryString(connection, "SELECT match_method FROM source_station_links WHERE source_station_id='de:global'"));
                assertEquals("EXACT_STOP_CODE", queryString(connection, "SELECT match_method FROM source_station_links WHERE source_station_id='source-code'"));
                assertEquals("COORDINATE_NAME_CHECKED", queryString(connection, "SELECT match_method FROM source_station_links WHERE source_station_id='source-coordinate'"));
                assertEquals("COORDINATE_UNIQUE", queryString(connection, "SELECT match_method FROM source_station_links WHERE source_station_id='source-coordinate-unique'"));
                assertEquals("fixture-published", queryString(connection, "SELECT runtime_data_version FROM source_station_link_metadata WHERE source_id='VBB'"));
            }
        } catch (Exception exception) {
            throw new AssertionError("Source station links must remain exact and conservative", exception);
        }
    }

    private static void normalizationKeepsSearchableStationHints() {
        assertEquals("dortmund hbf", StopNameNormalizer.normalize(" Dortmund   Hbf "));
        assertEquals("do hoerde bf", StopNameNormalizer.normalize("DO-Hörde Bf"));
        assertEquals("kampstrasse u", StopNameNormalizer.normalize("Kampstraße U"));
        assertEquals("koeln deutz messe", StopNameNormalizer.normalize("Köln-Deutz/Messe"));
    }

    private static void synonymsAreGenerated() {
        List<Stop> stops = List.of(
                new Stop("S1", null, "Dortmund Hbf", 51.5, 7.45, null, 0, null),
                new Stop("S2", null, "DO-Hörde Bf", 51.48, 7.5, null, 0, null),
                new Stop("S3", null, "Kampstraße U", 51.51, 7.46, null, 0, null),
                new Stop("S4", null, "Stadthaus S-Bahn", 51.49, 7.47, null, 0, null)
        );

        Set<String> tokens = StopSearchTokenBuilder.build(stops, StopAreaBuilder.fromStops(stops))
                .tokens()
                .stream()
                .map(StopSearchToken::token)
                .collect(Collectors.toSet());

        assertContains(tokens, "dortmund");
        assertContains(tokens, "hbf");
        assertContains(tokens, "hauptbahnhof");
        assertContains(tokens, "bf");
        assertContains(tokens, "bahnhof");
        assertContains(tokens, "u");
        assertContains(tokens, "ubahn");
        assertContains(tokens, "s");
        assertContains(tokens, "sbahn");
    }

    private static void tokensAreGeneratedForStopsAndAreas() {
        List<Stop> stops = List.of(
                new Stop("A", null, "Central Station", 50.0, 8.0, null, 1, null),
                new Stop("A1", null, "Central Station Gleis 1", 50.0, 8.0, "A", 0, "1")
        );
        List<StopArea> areas = StopAreaBuilder.fromStops(stops);

        StopSearchTokenBuilder.StopSearchTokenBuildResult result = StopSearchTokenBuilder.build(stops, areas);

        boolean hasAreaToken = result.tokens().stream()
                .anyMatch(token -> token.stopId() == null
                        && "A".equals(token.areaId())
                        && "central".equals(token.token())
                        && "AREA_NAME".equals(token.tokenType()));
        boolean hasNormalizedStopToken = result.tokens().stream()
                .anyMatch(token -> "A1".equals(token.stopId())
                        && "central station gleis 1".equals(token.token())
                        && "NORMALIZED".equals(token.tokenType()));

        assertTrue(hasAreaToken, "Expected area-name token for parent area");
        assertTrue(hasNormalizedStopToken, "Expected normalized full stop-name token");
    }

    private static void stopAreaReportSummarizesMembership() {
        List<Stop> stops = List.of(
                new Stop("P", null, "Parent", 50.0, 8.0, null, 1, null),
                new Stop("C1", null, "Child 1", 50.0, 8.0, "P", 0, null),
                new Stop("C2", null, "Child 2", 50.0, 8.0, "P", 0, null),
                new Stop("SOLO", null, "Solo", 50.0, 8.0, null, 0, null)
        );

        StopAreaReporter.StopAreaStats stats = StopAreaReporter.summarize(stops, StopAreaBuilder.fromStops(stops));

        assertEquals(2, stats.stopAreaCount());
        assertEquals(1, stats.singleStopAreas());
        assertEquals(1, stats.areasWithoutParentStation());
        assertEquals("P", stats.largestStopAreas().getFirst().areaId());
        assertEquals(3, stats.largestStopAreas().getFirst().stopCount());
    }

    private static void gtfsTimesStillSupportServiceDayOverflow() {
        assertEquals(88_200, GtfsTimeParser.toSecondsSinceServiceDayStart("24:30:00"));
        assertEquals(90_600, GtfsTimeParser.toSecondsSinceServiceDayStart("25:10:00"));
    }

    private static void stopAreaProfilesTrustRouteTypeOverRailLikeBusLabel() {
        Path database = null;
        try {
            database = Files.createTempFile("ixit-stop-profile-", ".sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE area_route_service_summary (
                            area_id TEXT NOT NULL,
                            route_id TEXT NOT NULL,
                            route_type INTEGER,
                            line_label TEXT,
                            stop_time_count INTEGER NOT NULL,
                            trip_count INTEGER NOT NULL
                        )
                        """);
                statement.execute("""
                        INSERT INTO area_route_service_summary
                            (area_id, route_id, route_type, line_label, stop_time_count, trip_count)
                        VALUES ('H_AKKU', 'BUS_S8', 3, 'S8', 26, 13)
                        """);
            }

            StopArea area = new StopArea("H_AKKU", "Hagen Akku Hawker", 51.35, 7.43, 1);
            StopAreaProfile profile = new StopAreaProfileBuilder(List.of(), List.of(area))
                    .buildFromDatabase(database)
                    .profiles()
                    .getFirst();

            assertEquals("BUS_ONLY", profile.profileClass());
            assertEquals(false, profile.hasRailService());
            assertEquals(true, profile.hasBus());
            assertEquals(true, profile.busOnly());
        } catch (Exception exception) {
            throw new AssertionError("Expected bus route type to override rail-like line label", exception);
        } finally {
            if (database != null) {
                try {
                    Files.deleteIfExists(database);
                } catch (IOException ignored) {
                    // Temporary self-test cleanup only.
                }
            }
        }
    }

    private static void databaseBackedHubProfilesPreserveTransferReferenceCounts() {
        Path database = null;
        try {
            database = Files.createTempFile("ixit-hub-profile-", ".sqlite");
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 var statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TABLE area_route_service_summary (
                            area_id TEXT NOT NULL,
                            route_id TEXT NOT NULL,
                            route_type INTEGER,
                            line_label TEXT,
                            stop_time_count INTEGER NOT NULL,
                            trip_count INTEGER NOT NULL
                        )
                        """);
                statement.execute("CREATE TABLE stop_area_members (area_id TEXT NOT NULL, stop_id TEXT NOT NULL)");
                statement.execute("CREATE TABLE transfers (from_stop_id TEXT NOT NULL, to_stop_id TEXT NOT NULL)");
                statement.execute("INSERT INTO stop_area_members VALUES ('P', 'C1'), ('P', 'C2')");
                statement.execute("INSERT INTO transfers VALUES ('C1', 'C2'), ('C1', 'C1')");
            }

            List<Stop> stops = List.of(
                    new Stop("C1", null, "Example 1", 50.0, 8.0, "P", 0, null),
                    new Stop("C2", null, "Example 2", 50.0, 8.0, "P", 0, null)
            );
            StopArea area = new StopArea("P", "Example", 50.0, 8.0, 2);
            var profile = HubProfileBuilder.databaseBacked(stops, List.of(area))
                    .buildFromDatabase(database)
                    .profiles()
                    .getFirst();

            assertEquals(13, profile.transferCandidateScore());
            assertTrue(profile.explanation().contains("transfer_refs=3"), "Expected from/to transfer references without double-counting same-stop transfers");
        } catch (Exception exception) {
            throw new AssertionError("Expected database-backed HubProfile transfer counts", exception);
        } finally {
            if (database != null) {
                try {
                    Files.deleteIfExists(database);
                } catch (IOException ignored) {
                    // Temporary self-test cleanup only.
                }
            }
        }
    }

    private static void canonicalStopAreasGroupStationFamilies() {
        List<StopArea> areas = List.of(
                new StopArea("M_KS", "Minden, Kaiserstraße", 52.2880, 8.9160, 2),
                new StopArea("M_BF", "Minden, Bahnhof", 52.2883, 8.9161, 1),
                new StopArea("M_ZOB", "Minden, ZOB", 52.2884, 8.9162, 1)
        );
        List<StopAreaProfile> profiles = List.of(
                new StopAreaProfile("M_KS", "REGIONAL_RAIL", 2, 2, 8, 80, 400, "2,3", "RE6,RE70,S1", true, true, false, false, true, false, false, true, 250, "rail-heavy technical area"),
                new StopAreaProfile("M_BF", "BUS_ONLY", 1, 0, 3, 30, 120, "3", "10,509", false, false, false, false, true, true, true, true, 80, "bus-only station label"),
                new StopAreaProfile("M_ZOB", "BUS_ONLY", 1, 0, 5, 50, 180, "3", "6,10,509", false, false, false, false, true, true, false, false, 70, "bus-only ZOB")
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult result = new CanonicalStopAreaBuilder(areas, profiles).build();

        assertEquals(1, result.canonicalAreas().size());
        assertEquals("Minden Bahnhof", result.canonicalAreas().getFirst().canonicalDisplayName());
        assertEquals("M_KS", result.canonicalAreas().getFirst().primaryStopAreaId());
        assertEquals(3, result.members().size());
        assertTrue(result.members().stream().anyMatch(member -> "M_KS".equals(member.areaId()) && "PRIMARY_RAIL".equals(member.memberRole())), "Expected rail area as primary member");
        assertTrue(result.members().stream().anyMatch(member -> "M_BF".equals(member.areaId()) && "BUS_FEEDER".equals(member.memberRole())), "Expected Bahnhof bus area as feeder");
        assertTrue(result.members().stream().anyMatch(member -> "M_ZOB".equals(member.areaId()) && "BUS_FEEDER".equals(member.memberRole())), "Expected ZOB as feeder");
        assertEquals("Hamburg Hbf", StopAreaNameHarmonizer.harmonize(
                "CAN_HH",
                "Hamburg, HBF/Kirchenallee",
                profiles.getFirst(),
                null,
                "TEST"
        ).displayName());
        assertEquals("Stendal Bahnhof", StopAreaNameHarmonizer.harmonize(
                "CAN_ST",
                "Stendal, Bahnhofstr.",
                profiles.getFirst(),
                null,
                "TEST"
        ).displayName());
        assertEquals("Minden Bahnhof", StopAreaNameHarmonizer.harmonize(
                "CAN_MI",
                "Minden, Kaiserstraße",
                profiles.getFirst(),
                "Minden, Fr-Wilhelm-Str/Bahnhof",
                "TEST"
        ).displayName());
        assertEquals("Berlin", StopAreaNameHarmonizer.cityName("S+U Berlin Hauptbahnhof"));
        assertEquals("Berlin", StopAreaNameHarmonizer.cityName("Hauptbahnhof, S+U Berlin"));
        assertEquals("Berlin", StopAreaNameHarmonizer.cityName("S Ostbahnhof Berlin"));
        assertEquals("Hbf", StopAreaNameHarmonizer.stationName("S+U Berlin Hbf", "Berlin"));
        assertEquals("Hbf", StopAreaNameHarmonizer.stationName("Iserlohn Hbf", "Iserlohn"));
        assertEquals("Iserlohnerheide Bf",
                StopAreaNameHarmonizer.stationName("Iserlohnerheide Bf", "Iserlohn"));
        assertEquals("Erzb.-Bruno-Str.",
                StopAreaNameHarmonizer.stationName("Xanten.Erzb.-Bruno-Str.", "Xanten"));
    }

    private static void canonicalStopAreasPreferSpecificRailPlaceOverBareMunicipality() {
        assertSpecificRailPlace(
                "M_MARIENPLATZ",
                "München",
                "Marienplatz",
                "München Marienplatz",
                "Marienplatz, München"
        );
        assertSpecificRailPlace(
                "MH_STYRUM",
                "Mülheim an der Ruhr",
                "Styrum Bahnhof",
                "Styrum Bahnhof",
                "Styrum, Mülheim an der Ruhr"
        );
        assertSpecificRailPlace(
                "KA_DURLACH",
                "Karlsruhe",
                "Durlach",
                "Karlsruhe Durlach",
                "Durlach, Karlsruhe"
        );
    }

    private static void canonicalStopAreasPreferGenericCityRailStationOverSpecialAlias() {
        String areaId = "CITY_RAIL";
        String city = "Beispielstadt";
        StopArea area = new StopArea(areaId, city, 48.137, 11.575, 10);
        StopAreaProfile profile = new StopAreaProfile(
                areaId,
                "RAIL",
                10,
                4,
                20,
                800,
                4_000,
                "2,3",
                "ICE,RE1,RB2,100",
                true,
                true,
                false,
                false,
                true,
                false,
                true,
                false,
                2_500,
                "city-named mainline station"
        );
        List<StopAreaAlias> aliases = List.of(
                new StopAreaAlias(
                        areaId,
                        city,
                        StopNameNormalizer.normalize(city),
                        "CANONICAL",
                        "AREA_NAME",
                        100
                ),
                new StopAreaAlias(
                        areaId,
                        "Bahnhof, " + city,
                        StopNameNormalizer.normalize("Bahnhof, " + city),
                        "CANONICAL",
                        "STOP_NAME",
                        70
                ),
                new StopAreaAlias(
                        areaId,
                        "Bergbahnhof, " + city,
                        StopNameNormalizer.normalize("Bergbahnhof, " + city),
                        "CANONICAL",
                        "STOP_NAME",
                        70
                )
        );
        StopAreaCity resolvedCity = new StopAreaCity(
                areaId,
                "fixture",
                city,
                "Stadt",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "fixture",
                "fixture"
        );

        CanonicalStopArea family = new CanonicalStopAreaBuilder(
                List.of(area),
                List.of(profile),
                aliases,
                List.of(resolvedCity)
        ).build().canonicalAreas().getFirst();

        assertEquals("Beispielstadt Bahnhof", family.canonicalDisplayName());
        assertEquals("Bahnhof, Beispielstadt", StopAreaPublicDisplayNameFormatter.publicDisplayName(family));
    }

    private static void canonicalStopAreasDoNotTreatDistrictStopsAsMunicipalityOnly() {
        String areaId = "KA_DURLACH_HUB";
        StopArea area = new StopArea(areaId, "Durlach Hubstraße", 49.007, 8.472, 1);
        StopAreaProfile profile = new StopAreaProfile(
                areaId,
                "RAIL",
                1,
                1,
                1,
                100,
                1_000,
                "2",
                "S5",
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false,
                500,
                "district rail stop"
        );
        StopAreaCity city = new StopAreaCity(
                areaId,
                "08212000",
                "Karlsruhe",
                "Stadt",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "fixture",
                "fixture"
        );
        List<StopAreaAlias> aliases = List.of(
                new StopAreaAlias(
                        areaId,
                        "Karlsruhe Durlach Hubstraße",
                        StopNameNormalizer.normalize("Karlsruhe Durlach Hubstraße"),
                        "CITY_QUALIFIED",
                        "STOP_NAME",
                        65
                )
        );

        CanonicalStopArea family = new CanonicalStopAreaBuilder(
                List.of(area),
                List.of(profile),
                aliases,
                List.of(city)
        ).build().canonicalAreas().getFirst();

        assertEquals("Durlach Hubstraße", family.canonicalDisplayName());
        assertEquals("Karlsruhe", family.cityName());
        assertEquals("Durlach Hubstraße", family.stationName());
    }

    private static void assertSpecificRailPlace(
            String areaId,
            String city,
            String stopName,
            String expectedCanonicalName,
            String expectedPublicName
    ) {
        StopArea area = new StopArea(
                areaId,
                city,
                48.137,
                11.575,
                1
        );
        StopAreaProfile profile = new StopAreaProfile(
                areaId,
                "URBAN_RAIL",
                1,
                2,
                8,
                300,
                1_500,
                "1,2",
                "U3,U6,S1,S8",
                true,
                false,
                true,
                false,
                false,
                false,
                false,
                false,
                900,
                "city-only station parent"
        );
        List<StopAreaAlias> aliases = List.of(
                new StopAreaAlias(
                        areaId,
                        city,
                        StopNameNormalizer.normalize(city),
                        "CANONICAL",
                        "AREA_NAME",
                        100
                ),
                new StopAreaAlias(
                        areaId,
                        stopName,
                        StopNameNormalizer.normalize(stopName),
                        "CANONICAL",
                        "STOP_NAME",
                        70
                ),
                new StopAreaAlias(
                        areaId,
                        StopNameNormalizer.normalize(city + " " + stopName),
                        StopNameNormalizer.normalize(city + " " + stopName),
                        "CITY_QUALIFIED",
                        "STOP_NAME",
                        65
                )
        );

        StopAreaCity resolvedCity = new StopAreaCity(
                areaId,
                "fixture",
                city,
                "Stadt",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "fixture",
                "fixture"
        );
        CanonicalStopArea family = new CanonicalStopAreaBuilder(
                List.of(area),
                List.of(profile),
                aliases,
                List.of(resolvedCity)
        ).build().canonicalAreas().getFirst();

        assertEquals(expectedCanonicalName, family.canonicalDisplayName());
        assertEquals(city, family.cityName());
        assertEquals(stopName, family.stationName());
        assertEquals(expectedPublicName, StopAreaPublicDisplayNameFormatter.publicDisplayName(family));
    }

    private static void canonicalStopAreasMergeNearbyPrimaryCandidates() {
        List<StopArea> areas = List.of(
                new StopArea("B_RAIL", "Bremen Hbf", 53.0830, 8.8130, 2),
                new StopArea("B_TRAM", "Bremen Hauptbahnhof", 53.0832, 8.8132, 2),
                new StopArea("B_ZOB", "Bremen Hbf/ZOB", 53.0833, 8.8133, 1)
        );
        List<StopAreaProfile> profiles = List.of(
                new StopAreaProfile("B_RAIL", "MAIN_RAIL", 2, 2, 12, 120, 800, "2,3", "ICE,RE1,RS1", true, true, false, false, true, false, true, true, 500, "rail-heavy station"),
                new StopAreaProfile("B_TRAM", "URBAN_RAIL", 2, 0, 8, 300, 900, "0,3", "1,4,6,24", false, false, false, true, true, false, true, true, 900, "urban station forecourt"),
                new StopAreaProfile("B_ZOB", "BUS_ONLY", 1, 0, 5, 90, 260, "3", "24,25,740", false, false, false, false, true, true, true, true, 300, "bus feeder")
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult result = new CanonicalStopAreaBuilder(areas, profiles).build();

        assertEquals(1, result.canonicalAreas().size());
        assertEquals("B_RAIL", result.canonicalAreas().getFirst().primaryStopAreaId());
        assertEquals(3, result.members().size());
        assertTrue(result.members().stream().anyMatch(member ->
                "B_RAIL".equals(member.areaId())
                        && "PRIMARY_RAIL".equals(member.memberRole())
                        && member.primaryForSearch()
                        && member.primaryForRouting()
                        && member.visibleSuggestion()), "Expected rail member as visible search/routing primary");
        assertTrue(result.members().stream().anyMatch(member ->
                "B_TRAM".equals(member.areaId())
                        && "NEARBY_URBAN".equals(member.memberRole())
                        && !member.primaryForSearch()
                        && member.primaryForRouting()
                        && !member.visibleSuggestion()), "Expected urban station member as hidden routing member, not a separate suggestion");
        assertTrue(result.members().stream().anyMatch(member ->
                "B_ZOB".equals(member.areaId())
                        && "BUS_FEEDER".equals(member.memberRole())
                        && !member.primaryForSearch()
                        && !member.primaryForRouting()
                        && !member.visibleSuggestion()), "Expected ZOB as hidden feeder");
        assertEquals(1, (int) result.members().stream()
                .filter(member -> member.visibleSuggestion())
                .count());
        assertTrue(result.transferEdges().stream().anyMatch(edge ->
                "B_TRAM".equals(edge.fromAreaId())
                        && "B_RAIL".equals(edge.toAreaId())
                        && edge.minTransferMinutes() > 0), "Expected internal transfer from urban member to rail primary");
    }

    private static void canonicalStopAreasKeepNearbyBusStopsSeparate() {
        List<StopArea> areas = List.of(
                new StopArea("H_WEHR", "Hagen Wehringhausen", 51.3540, 7.4450, 1),
                new StopArea("H_AKKU", "Hagen Akku Hawker", 51.3530, 7.4440, 1)
        );
        List<StopAreaProfile> profiles = List.of(
                new StopAreaProfile("H_WEHR", "RAIL", 1, 1, 1, 79, 158, "2", "S8", true, true, false, false, false, false, false, false, 180, "rail stop"),
                new StopAreaProfile("H_AKKU", "BUS_ONLY", 1, 0, 6, 400, 800, "3", "542,E1,E27,E34,S8,S9", false, false, false, false, true, true, false, false, 80, "bus stop")
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult result = new CanonicalStopAreaBuilder(areas, profiles).build();

        assertEquals(2, result.canonicalAreas().size());
        assertTrue(result.members().stream().anyMatch(member ->
                "H_AKKU".equals(member.areaId())
                        && "CAN_H_AKKU".equals(member.canonicalAreaId())
                        && "PRIMARY_BUS".equals(member.memberRole())
                        && member.primaryForSearch()
                        && member.primaryForRouting()
                        && member.visibleSuggestion()),
                "Expected nearby bus stop to remain its own visible routing family");
    }

    private static void canonicalStopAreasKeepDistinctDistrictStationsSeparate() {
        List<StopArea> areas = List.of(
                new StopArea("B_HBF", "Bremen Hbf", 53.0830, 8.8130, 2),
                new StopArea("B_NEUSTADT", "Bremen Neustadt", 53.0700, 8.8000, 2)
        );
        List<StopAreaProfile> profiles = List.of(
                new StopAreaProfile("B_HBF", "MAIN_RAIL", 2, 2, 12, 120, 800, "2,3", "ICE,RE1,RS1", true, true, false, false, true, false, true, true, 500, "main station"),
                new StopAreaProfile("B_NEUSTADT", "RAIL", 2, 2, 4, 40, 180, "2", "RB58,RS3,RS4", true, true, false, false, false, false, false, false, 180, "district station")
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult result = new CanonicalStopAreaBuilder(areas, profiles).build();

        assertEquals(2, result.canonicalAreas().size());
        assertTrue(result.members().stream().anyMatch(member ->
                "B_NEUSTADT".equals(member.areaId())
                        && "CAN_B_NEUSTADT".equals(member.canonicalAreaId())
                        && member.primaryForSearch()
                        && member.primaryForRouting()
                        && member.visibleSuggestion()), "Expected district station to remain its own visible routing family");
    }

    private static void canonicalStopAreasDoNotInventBahnhofForUrbanStreetStops() {
        List<StopArea> areas = List.of(
                new StopArea("DO_WILLEM", "DO-Willem-van-Vloten-Str", 51.49, 7.50, 1),
                new StopArea("DO_KARL", "Dortmund Karl-Liebknecht-Str.", 51.50, 7.51, 1)
        );
        List<StopAreaProfile> profiles = List.of(
                new StopAreaProfile("DO_WILLEM", "URBAN_RAIL", 1, 0, 1, 120, 360, "0", "U41", false, false, true, false, false, false, false, false, 120, "urban rail street stop"),
                new StopAreaProfile("DO_KARL", "URBAN_RAIL", 1, 0, 1, 90, 270, "0", "U41", false, false, true, false, false, false, false, false, 100, "urban rail street stop")
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult result = new CanonicalStopAreaBuilder(areas, profiles).build();

        assertTrue(result.canonicalAreas().stream().anyMatch(area ->
                "DO_WILLEM".equals(area.primaryStopAreaId())
                        && "Willem-van-Vloten-Str., Dortmund".equals(StopAreaPublicDisplayNameFormatter.publicDisplayName(area))),
                "Expected Willem-van-Vloten street stop without invented Bahnhof");
        assertTrue(result.canonicalAreas().stream().anyMatch(area ->
                "DO_KARL".equals(area.primaryStopAreaId())
                        && "Karl-Liebknecht-Str., Dortmund".equals(StopAreaPublicDisplayNameFormatter.publicDisplayName(area))),
                "Expected Karl-Liebknecht street stop without invented Bahnhof");
    }

    private static void canonicalStopAreasUseCityAliasForTechnicalStationLevels() {
        List<StopArea> areas = List.of(
                new StopArea("S_OBEN", "Hauptbahnhof (oben)", 48.783, 9.181, 1),
                new StopArea("S_TIEF", "Hauptbahnhof (tief)", 48.782, 9.181, 1)
        );
        List<StopAreaProfile> profiles = List.of(
                new StopAreaProfile("S_OBEN", "MAIN_RAIL", 1, 8, 30, 5000, 20000, "2", "ICE,IC,MEX", true, true, false, false, false, false, true, true, 500, "main rail"),
                new StopAreaProfile("S_TIEF", "MAIN_RAIL", 1, 6, 12, 3000, 12000, "2", "S1,S2,S3", true, true, false, false, false, false, true, true, 250, "s-bahn rail")
        );
        List<StopAreaAlias> aliases = List.of(
                new StopAreaAlias("S_OBEN", "Stuttgart Hbf", "stuttgart hbf", "CANONICAL", "STOP_NAME", 70),
                new StopAreaAlias("S_OBEN", "Hauptbahnhof (oben)", "hauptbahnhof oben", "CANONICAL", "AREA_NAME", 100),
                new StopAreaAlias("S_TIEF", "Stuttgart Hbf (tief)", "stuttgart hbf tief", "CANONICAL", "STOP_NAME", 70)
        );

        CanonicalStopAreaBuilder.CanonicalStopAreaBuildResult result =
                new CanonicalStopAreaBuilder(areas, profiles, aliases).build();

        CanonicalStopArea family = result.canonicalAreas().stream()
                .filter(area -> "S_OBEN".equals(area.primaryStopAreaId()))
                .findFirst()
                .orElseThrow();

        assertEquals("Stuttgart Hbf", family.canonicalDisplayName());
        assertEquals("Hauptbahnhof, Stuttgart", StopAreaPublicDisplayNameFormatter.publicDisplayName(family));

        StopAreaCity stuttgart = new StopAreaCity(
                "S_TIEF",
                "08111000",
                "Stuttgart",
                "Stadt",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "2025-01-01",
                "fixture"
        );
        StopAreaDisplayName lowerLevel = StopAreaPublicDisplayNameFormatter.forFamilyMember(
                areas.get(1),
                family.canonicalAreaId(),
                stuttgart
        );
        assertEquals("Hauptbahnhof, Stuttgart", lowerLevel.publicDisplayName());
        assertEquals("Hauptbahnhof", lowerLevel.publicStopName());
    }

    private static void publicStopAreaDisplayNamesUseStationCityPattern() {
        assertEquals("Hauptbahnhof, Dortmund", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_DO",
                "Dortmund Hbf",
                "Dortmund Hbf",
                "Dortmund",
                "Hbf"
        )));
        assertEquals("Bahnhof, Minden", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_MI",
                "Minden Bahnhof",
                "Minden, Kaiserstrasse",
                "Minden",
                "Bahnhof"
        )));
        assertEquals("Hörde, Dortmund", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_DO_HOERDE",
                "Dortmund Hörde Bahnhof",
                "Dortmund Hörde Bahnhof",
                "Dortmund Hörde",
                "Bahnhof"
        )));
        assertEquals("Reutin, Lindau", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_LI",
                "Reutin Bahnhof, Lindau (Bodensee)",
                "Reutin Bahnhof, Lindau (Bodensee)",
                "",
                ""
        )));
        assertEquals("Sterkrade, Oberhausen", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_OB",
                "OB Sterkrade Bf.",
                "OB Sterkrade Bf.",
                "",
                ""
        )));
        assertEquals("Walsum Rathaus, Duisburg", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_DU",
                "Duisburg Walsum Rathaus",
                "Duisburg Walsum Rathaus",
                "",
                ""
        )));
        assertEquals("Willem-van-Vloten-Str., Dortmund", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_DO_WILLEM",
                "Dortmund Willem-van-Vloten-Str",
                "Dortmund Willem-van-Vloten-Str",
                "Dortmund",
                "Bahnhof"
        )));
        assertEquals("Karl-Liebknecht-Str., Dortmund", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_DO_KARL",
                "Dortmund Karl-Liebknecht-Strasse",
                "Dortmund Karl-Liebknecht-Strasse",
                "Dortmund",
                "Bahnhof"
        )));
        assertEquals("Ostbahnhof, Berlin", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_BER_OST",
                "S Ostbahnhof",
                "S Ostbahnhof Berlin",
                "Berlin",
                "Ostbahnhof"
        )));
        assertEquals("Ostbahnhof, Berlin", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_BER_OST_PARENTHESES",
                "S Ostbahnhof",
                "S Ostbahnhof (Berlin)",
                "S",
                "Ostbahnhof"
        )));
        assertEquals("Hauptbahnhof, Berlin", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_BER_HBF_MODE_SUFFIX",
                "Hauptbahnhof Berlin, S+U",
                "Hauptbahnhof Berlin, S+U",
                "",
                ""
        )));
        assertEquals("Ostbahnhof, Berlin", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_BER_OST_MODE_SUFFIX",
                "Ostbahnhof Berlin, S+U",
                "Ostbahnhof Berlin, S+U",
                "",
                ""
        )));
        assertEquals("Hauptbahnhof, Berlin", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_BER_HBF_POSTPOSED_MODE_CITY",
                "Hauptbahnhof, S+U Berlin",
                "Hauptbahnhof, S+U Berlin",
                "",
                ""
        )));
        assertEquals("Bahnhof, Freising", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_FREISING",
                "Freising",
                "Freising",
                "Freising",
                ""
        )));
        assertEquals("Bahnhof Wilhelmshöhe, Kassel", StopAreaPublicDisplayNameFormatter.publicDisplayName(canonicalArea(
                "CAN_KASSEL_WILHELMSHOEHE",
                "Kassel Rotes Kreuz",
                "Kassel Bahnhof Wilhelmshöhe",
                "Kassel Rotes Kreuz",
                ""
        )));

        StopAreaCity hamburg = new StopAreaCity(
                "CAN_HAMBURG_PRIMARY",
                "02000000",
                "Hamburg",
                "Stadt",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "2025-01-01",
                "fixture"
        );
        assertEquals("Hauptbahnhof, Hamburg", StopAreaPublicDisplayNameFormatter.forMember(
                canonicalArea(
                        "CAN_HAMBURG",
                        "Hamburg Hbf Süd",
                        "Hamburg, Hamburg Hbf",
                        "Hamburg",
                        "Hbf Süd"
                ),
                "CAN_HAMBURG_PRIMARY",
                hamburg
        ).publicDisplayName());

        StopAreaCity oberding = new StopAreaCity(
                "CAN_MUC_AIRPORT_PRIMARY",
                "09178133",
                "Oberding",
                "Gemeinde",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "2025-01-01",
                "fixture"
        );
        assertEquals("Flughafen München", StopAreaPublicDisplayNameFormatter.forMember(
                canonicalArea(
                        "CAN_MUC_AIRPORT",
                        "Flughafen München",
                        "Flughafen München",
                        "Flughafen München",
                        ""
                ),
                "CAN_MUC_AIRPORT_PRIMARY",
                oberding
        ).publicDisplayName());
    }

    private static void publicStopAreaDisplayNamesStripMatchingCityPrefixes() {
        StopAreaDisplayName duesseldorf = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("D_STEINSTR", "D-Steinstr.", 51.223, 6.787, 1),
                resolvedCity("D_STEINSTR", "Düsseldorf")
        );
        assertEquals("Steinstr., Düsseldorf", duesseldorf.publicDisplayName());
        assertEquals("Steinstr.", duesseldorf.publicStopName());
        assertTrue(duesseldorf.source().contains("CITY_PREFIX"), "Expected city-prefix display source");

        StopAreaDisplayName alreadyQualified = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("D_STEINSTR_QUALIFIED", "D-Steinstr., Düsseldorf", 51.223, 6.787, 1),
                resolvedCity("D_STEINSTR_QUALIFIED", "Düsseldorf")
        );
        assertEquals("Steinstr., Düsseldorf", alreadyQualified.publicDisplayName());
        assertEquals("Steinstr.", alreadyQualified.publicStopName());

        StopAreaDisplayName dortmund = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("DO_HOERDE", "DO-Hörde Bf", 51.489, 7.500, 1),
                resolvedCity("DO_HOERDE", "Dortmund")
        );
        assertEquals("Hörde, Dortmund", dortmund.publicDisplayName());
        StopAreaDisplayName chainedDortmund = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("DO_HUCKARDE", "Dortmund DO-Huckarde S", 51.533, 7.402, 1),
                resolvedCity("DO_HUCKARDE", "Dortmund")
        );
        assertEquals("Huckarde S, Dortmund", chainedDortmund.publicDisplayName());
        assertTrue(chainedDortmund.source().contains("CITY_PREFIX_CHAIN"), "Expected chained city-prefix source");

        StopAreaDisplayName frankfurt = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("F_SOUTH", "Frankfurt Süd", 50.099, 8.686, 1),
                resolvedCity("F_SOUTH", "Frankfurt am Main")
        );
        assertEquals("Süd, Frankfurt am Main", frankfurt.publicDisplayName());
        StopAreaDisplayName frankfurtCanonicalFields = StopAreaPublicDisplayNameFormatter.forMember(
                canonicalArea(
                        "F_ACHENBACH",
                        "Frankfurt Achenbachstraße",
                        "Frankfurt (Main) Achenbachstraße",
                        "Frankfurt am Main",
                        "Frankfurt Achenbachstraße"
                ),
                "F_ACHENBACH",
                resolvedCity("F_ACHENBACH", "Frankfurt am Main")
        );
        assertEquals("Achenbachstr., Frankfurt am Main", frankfurtCanonicalFields.publicDisplayName());
        StopAreaDisplayName frankfurtShort = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("FFM_SCHIELE", "Ffm Schielestraße Ost", 50.128, 8.746, 1),
                resolvedCity("FFM_SCHIELE", "Frankfurt am Main")
        );
        assertEquals("Schielestr. Ost, Frankfurt am Main", frankfurtShort.publicDisplayName());
        StopAreaDisplayName frankfurtShortHyphen = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("FFM_SCHIELE_HYPHEN", "Ffm-Schielestraße Ost", 50.128, 8.746, 1),
                resolvedCity("FFM_SCHIELE_HYPHEN", "Frankfurt am Main")
        );
        assertEquals("Schielestr. Ost, Frankfurt am Main", frankfurtShortHyphen.publicDisplayName());
        StopAreaDisplayName frankfurtMainQualifier = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("F_MAIN_HBF", "Frankfurt (Main) Hbf", 50.107, 8.663, 1),
                resolvedCity("F_MAIN_HBF", "Frankfurt am Main")
        );
        assertEquals("Hauptbahnhof, Frankfurt am Main", frankfurtMainQualifier.publicDisplayName());
        StopAreaDisplayName frankfurtTechnicalQualifier = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("F_MAIN_HBF_MESSE", "Frankfurt (Messe) Hbf", 50.107, 8.663, 1),
                resolvedCity("F_MAIN_HBF_MESSE", "Frankfurt am Main")
        );
        assertEquals("(Messe) Hauptbahnhof, Frankfurt am Main", frankfurtTechnicalQualifier.publicDisplayName());
        StopAreaDisplayName limburgQualifier = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("LIMBURG_ZOB", "Limburg (Lahn) ZOB Nord", 50.385, 8.064, 1),
                resolvedCity("LIMBURG_ZOB", "Limburg a.d.Lahn")
        );
        assertEquals("ZOB Nord, Limburg a.d.Lahn", limburgQualifier.publicDisplayName());
        StopAreaDisplayName ottersbergQualifier = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("OTTERSBERG_BF", "Ottersberg(b Bremen) Bahnhof", 53.111, 9.144, 1),
                resolvedCity("OTTERSBERG_BF", "Ottersberg")
        );
        assertEquals("Bahnhof, Ottersberg", ottersbergQualifier.publicDisplayName());

        CityPrefixAliasResolver.Builder mannheimBuilder = CityPrefixAliasResolver.builder();
        mannheimBuilder.observe("MA-Hauptbahnhof Süd", "Mannheim");
        StopAreaDisplayName mannheimComma = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("MA_HBF_SOUTH", "Lindenhof, MA Hauptbahnhof Süd", 49.477, 8.468, 1),
                resolvedCity("MA_HBF_SOUTH", "Mannheim"),
                mannheimBuilder.build()
        );
        assertEquals("Hauptbahnhof Süd, Mannheim", mannheimComma.publicDisplayName());

        StopAreaDisplayName stuttgart = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("S_MARIENPLATZ", "Stuttgart Marienplatz", 48.764, 9.168, 1),
                resolvedCity("S_MARIENPLATZ", "Stuttgart")
        );
        assertEquals("Marienplatz, Stuttgart", stuttgart.publicDisplayName());
        StopAreaDisplayName muenchen = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("M_MARIENPLATZ", "München Marienplatz", 48.137, 11.575, 1),
                resolvedCity("M_MARIENPLATZ", "München")
        );
        assertEquals("Marienplatz, München", muenchen.publicDisplayName());
        StopAreaDisplayName messkirch = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("MESSKIRCH_SCHOOL", "Messkirch Hauptschule", 47.994, 9.114, 1),
                resolvedCity("MESSKIRCH_SCHOOL", "Meßkirch")
        );
        assertEquals("Hauptschule, Meßkirch", messkirch.publicDisplayName());

        StopAreaDisplayName stolbergAttachedQualifier = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("STOLBERG_GL44", "Stolberg(Rheinl)Hauptbahnhof Gl.44", 50.773, 6.226, 1),
                resolvedCity("STOLBERG_GL44", "Stolberg")
        );
        assertEquals("Hauptbahnhof Gl.44, Stolberg", stolbergAttachedQualifier.publicDisplayName());

        assertDisplayName("Wörth (Isar), Abzw. Bahnhof", "Wörth a.d.Isar",
                "Abzw. Bahnhof, Wörth a.d.Isar");
        assertDisplayName("Offenbach (Main)-Zentrum Marktplatz", "Offenbach am Main",
                "Zentrum Marktplatz, Offenbach am Main");
        assertDisplayName("Benneckenstein Benneckenstein Gleis 2", "Oberharz am Brocken",
                "Benneckenstein Gleis 2, Oberharz am Brocken");

        StopAreaDisplayName iserlohnerheide = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("ISERLOHN_HEIDE", "Iserlohnerheide Kirche", 51.387, 7.696, 1),
                resolvedCity("ISERLOHN_HEIDE", "Iserlohn")
        );
        assertEquals("Iserlohnerheide Kirche, Iserlohn", iserlohnerheide.publicDisplayName());
        assertEquals("Iserlohnerheide Kirche", iserlohnerheide.publicStopName());

        assertDisplayName("Iserlohn Iserlohnerheide", "Iserlohn", "Iserlohnerheide, Iserlohn");
        assertDisplayName("Iserlohnerheide", "Iserlohn", "Iserlohnerheide, Iserlohn");

        assertDisplayName("Beispielstraße", "Musterstadt", "Beispielstr., Musterstadt");
        assertDisplayName("Beispielstrasse", "Musterstadt", "Beispielstr., Musterstadt");
        assertDisplayName("Beispielstr", "Musterstadt", "Beispielstr., Musterstadt");
        assertDisplayName("Beispielstr.", "Musterstadt", "Beispielstr., Musterstadt");
        assertDisplayName("Metzer Straße", "Düsseldorf", "Metzer Str., Düsseldorf");
        assertDisplayName("Metzer str.", "Düsseldorf", "Metzer Str., Düsseldorf");
        assertDisplayName("Metzer-Strasse", "Düsseldorf", "Metzer-Str., Düsseldorf");
        assertDisplayName("Erzb.-Bruno-Strasse", "Xanten", "Erzb.-Bruno-Str., Xanten");
        assertDisplayName("Willem-van-Vloten-straße", "Dortmund", "Willem-van-Vloten-Str., Dortmund");
        assertDisplayName("Straßenbahn Museum", "Musterstadt", "Straßenbahn Museum, Musterstadt");

        CityPrefixAliasResolver.Builder learnedBuilder = CityPrefixAliasResolver.builder();
        for (int index = 0; index < 20; index++) {
            learnedBuilder.observe("XN-Haltestelle " + index, "Xylophon");
            learnedBuilder.observe("KIT-Campus " + index, "Eggenstein-Leopoldshafen");
        }
        CityPrefixAliasResolver learnedResolver = learnedBuilder.build();
        StopAreaDisplayName learned = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("XN_MARKT", "XN-Marktplatz", 50.0, 8.0, 1),
                resolvedCity("XN_MARKT", "Xylophon"),
                learnedResolver
        );
        assertEquals("Marktplatz, Xylophon", learned.publicDisplayName());
        assertEquals(1, learnedResolver.learnedAliasCount());
        assertTrue(!learnedResolver.stripLeadingPrefix("KIT-Campus Nord", "Eggenstein-Leopoldshafen").stripped(),
                "Frequent institution names must not be learned as city prefixes");

        CityPrefixAliasResolver builtIn = CityPrefixAliasResolver.builtIn();
        assertTrue(!builtIn.stripLeadingPrefix("S-Bahnhof Gohlis", "Leipzig").stripped(),
                "S-Bahn mode marker must not be treated as a city prefix");
        assertTrue(!builtIn.stripLeadingPrefix("K.-Schumacher Br.", "Essen").stripped(),
                "Person initials must not be treated as a city prefix");
        assertTrue(!builtIn.stripLeadingPrefix("KIT-Campus Nord", "Eggenstein-Leopoldshafen").stripped(),
                "Institution names must not be treated as city prefixes");
    }

    private static void genericStationSuffixRequiresSpecificDesignation() {
        StopAreaDisplayName meerbusch = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("MEERBUSCH_OSTERATH", "Meerbusch Osterath Bf", 51.267, 6.621, 1),
                resolvedCity("MEERBUSCH_OSTERATH", "Meerbusch")
        );
        assertEquals("Osterath, Meerbusch", meerbusch.publicDisplayName());
        List<String> rules = DisplayNameTransformationRules.decode(
                DisplayNameTransformationRules.extract(meerbusch.explanation())
        );
        assertTrue(rules.contains(DisplayNameTransformationRules.STATION_ABBREVIATION_EXPANDED),
                "Expected Bahnhof abbreviation expansion before suffix removal");
        assertTrue(rules.contains(DisplayNameTransformationRules.GENERIC_STATION_SUFFIX_REMOVED),
                "Expected explainable generic Bahnhof suffix removal");

        assertDisplayName("Xanten Bahnhof", "Xanten", "Bahnhof, Xanten");
        assertDisplayName("Stuttgart Bad Cannstatt Bahnhof", "Stuttgart", "Bad Cannstatt, Stuttgart");
        assertDisplayName("Iserlohn Iserlohnerheide Bahnhof", "Iserlohn", "Iserlohnerheide, Iserlohn");
        assertDisplayName("Adenau Alter Bahnhof", "Adenau", "Alter Bahnhof, Adenau");
        assertDisplayName("Aarberg Post Bahnhof", "Aarberg", "Post Bahnhof, Aarberg");
        assertDisplayName("Berlin Anhalter Bahnhof", "Berlin", "Anhalter Bahnhof, Berlin");
        assertDisplayName("Konstanz Schweizer Bahnhof", "Konstanz", "Schweizer Bahnhof, Konstanz");
        assertDisplayName("Aesch BL Bahnhof", "Aesch", "BL Bahnhof, Aesch");
        assertDisplayName("Oberwil b. Zug Bahnhof", "Oberwil", "b. Zug Bahnhof, Oberwil");
        assertDisplayName("Wörth Abzw. Bahnhof", "Wörth", "Abzw. Bahnhof, Wörth");
        assertDisplayName("Aschersleben Richtung Bahnhof", "Aschersleben", "Richtung Bahnhof, Aschersleben");
        assertDisplayName("Musterstadt Metzer Str. Bahnhof", "Musterstadt", "Metzer Str. Bahnhof, Musterstadt");
        assertEquals("Danziger Bahnhof, Böblingen", StopAreaPublicDisplayNameFormatter.publicDisplayName(
                canonicalArea(
                        "BOEBLINGEN_DANZIGER",
                        "Böblingen Danziger Bahnhof",
                        "Böblingen Danziger Str.",
                        "Böblingen",
                        "Danziger Bahnhof"
                )
        ));
    }

    private static void majorCityDisplayNamesRemainStable() {
        assertDisplayName("DO-Hörde Bf", "Dortmund", "Hörde, Dortmund");
        assertDisplayName("D-Steinstr.", "Düsseldorf", "Steinstr., Düsseldorf");
        assertDisplayName("Ffm-Schielestraße Ost", "Frankfurt am Main", "Schielestr. Ost, Frankfurt am Main");
        assertDisplayName("Stuttgart Marienplatz", "Stuttgart", "Marienplatz, Stuttgart");
        assertDisplayName("München Marienplatz", "München", "Marienplatz, München");
        assertDisplayName("Hamburg Hbf", "Hamburg", "Hauptbahnhof, Hamburg");
        assertDisplayName("Berlin Ostbahnhof", "Berlin", "Ostbahnhof, Berlin");
    }

    private static void displayQualityBaselineClassifiesWithoutDeleting() {
        assertEquals("INSTITUTION_NAME",
                DisplayNameQualityBaselineBuilder.classifyPrefix("UNIL", "Lausanne").classification());
        assertEquals("INSTITUTION_NAME",
                DisplayNameQualityBaselineBuilder.classifyPrefix("HEIG", "Yverdon-les-Bains").classification());
        assertEquals("TRANSIT_OR_INFRASTRUCTURE_TERM",
                DisplayNameQualityBaselineBuilder.classifyPrefix("ZUP", "Emmendingen").classification());
        assertEquals("LOCALITY_CODE_CANDIDATE",
                DisplayNameQualityBaselineBuilder.classifyPrefix("OER", "Oer-Erkenschwick").classification());
        assertEquals("PRESERVED_ACRONYM",
                DisplayNameQualityBaselineBuilder.classifyPrefix("XYZ", "Berlin").classification());
        assertEquals("PRESERVE", DisplayNameQualityBaselineBuilder.ACTION_PRESERVE);
    }

    private static void assertDisplayName(String sourceName, String cityName, String expected) {
        StopAreaDisplayName displayName = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("REGRESSION_" + StopNameNormalizer.normalize(sourceName), sourceName, 50.0, 8.0, 1),
                resolvedCity("REGRESSION", cityName)
        );
        assertEquals(expected, displayName.publicDisplayName());
    }

    private static void displayNameTransformationsAreExplainable() {
        StopAreaDisplayName street = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("METZER_STR", "Metzer str.", 51.22, 6.78, 1),
                resolvedCity("METZER_STR", "Düsseldorf")
        );
        List<String> streetRules = DisplayNameTransformationRules.decode(
                DisplayNameTransformationRules.extract(street.explanation())
        );
        assertTrue(streetRules.contains(DisplayNameTransformationRules.STREET_SUFFIX_NORMALIZED),
                "Expected street suffix transformation rule");
        assertTrue(streetRules.contains(DisplayNameTransformationRules.STOP_CITY_COMPOSED),
                "Expected stop-city composition rule");
        assertTrue(street.explanation().contains("output=Metzer Str., Düsseldorf"),
                "Expected display-name output in explanation");

        StopAreaDisplayName cityCode = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("D_STEINSTR", "D-Steinstr.", 51.22, 6.78, 1),
                resolvedCity("D_STEINSTR", "Düsseldorf")
        );
        List<String> cityCodeRules = DisplayNameTransformationRules.decode(
                DisplayNameTransformationRules.extract(cityCode.explanation())
        );
        assertTrue(cityCodeRules.contains(DisplayNameTransformationRules.CITY_CODE_EXPANDED),
                "Expected city-code expansion rule");
        assertTrue(cityCodeRules.contains(DisplayNameTransformationRules.CITY_PREFIX_REMOVED),
                "Expected city-prefix removal rule");
        assertTrue(!cityCodeRules.contains(DisplayNameTransformationRules.STREET_SUFFIX_NORMALIZED),
                "Already normalized attached street suffix must not be reported as changed");

        StopAreaDisplayName station = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("HH_HBF", "Hamburg Hbf", 53.55, 10.0, 1),
                resolvedCity("HH_HBF", "Hamburg")
        );
        assertTrue(
                DisplayNameTransformationRules.decode(
                        DisplayNameTransformationRules.extract(station.explanation())
                ).contains(DisplayNameTransformationRules.STATION_ABBREVIATION_EXPANDED),
                "Expected station-abbreviation expansion rule"
        );

        StopAreaDisplayName locality = StopAreaPublicDisplayNameFormatter.forRawStopArea(
                new StopArea("ISERLOHNERHEIDE", "Iserlohnerheide Kirche", 51.39, 7.7, 1),
                resolvedCity("ISERLOHNERHEIDE", "Iserlohn")
        );
        assertTrue(
                DisplayNameTransformationRules.decode(
                        DisplayNameTransformationRules.extract(locality.explanation())
                ).contains(DisplayNameTransformationRules.LOCALITY_COMPOUND_PRESERVED),
                "Expected locality-compound preservation rule"
        );

        assertThrows(IllegalArgumentException.class,
                () -> DisplayNameTransformationRules.decode("UNKNOWN_RULE"));
        assertThrows(IllegalArgumentException.class,
                () -> DisplayNameTransformationRules.decode(
                        DisplayNameTransformationRules.STOP_CITY_COMPOSED
                                + "|"
                                + DisplayNameTransformationRules.STREET_SUFFIX_NORMALIZED
                ));

        DisplayNameAuditor.Accumulator invalidAudit = DisplayNameAuditor.accumulator(
                CityPrefixAliasResolver.builtIn()
        );
        invalidAudit.accept("INVALID_RULE", "Metzer Str.", "Düsseldorf",
                "Metzer Str., Düsseldorf", "UNKNOWN_RULE");
        assertEquals(false, invalidAudit.report().pass());
        assertEquals(1L, invalidAudit.report().invalidTransformationRuleRows());
    }

    private static void familyMembersKeepConcreteDisplayNames() {
        StopAreaDisplayName displayName = StopAreaPublicDisplayNameFormatter.forFamilyMember(
                new StopArea("H_SCHWENKE", "Hagen Schwenke", 51.35, 7.45, 1),
                "CAN_HAGEN_HBF"
        );

        assertEquals("H_SCHWENKE", displayName.areaId());
        assertEquals("CAN_HAGEN_HBF", displayName.canonicalAreaId());
        assertEquals("Schwenke, Hagen", displayName.publicDisplayName());
    }

    private static void stopAreaAliasesHandlePostposedCityNames() {
        List<StopArea> areas = List.of(
                new StopArea("LINDAU_REUTIN", "Reutin Bahnhof, Lindau (Bodensee)", 47.552, 9.704, 1)
        );

        Set<String> aliases = StopAreaAliasBuilder.build(List.of(), areas)
                .aliases()
                .stream()
                .map(StopAreaAlias::aliasNormalized)
                .collect(Collectors.toSet());

        assertContains(aliases, "lindau bahnhof");
        assertContains(aliases, "lindau bf");
        assertContains(aliases, "lindau bodensee bahnhof");
        assertContains(aliases, "reutin bahnhof lindau bodensee");
    }

    private static void stopAreaAliasesUseMunicipalityCityAndStreetVariants() {
        List<StopArea> areas = List.of(
                new StopArea("MAINZ_LERCHENBERG", "Lerchenberg Hindemithstr.", 49.959, 8.198, 1)
        );
        List<StopAreaCity> cities = List.of(
                new StopAreaCity(
                        "MAINZ_LERCHENBERG",
                        "07315000",
                        "Mainz",
                        "Stadt",
                        "BKG_VG250_GEOMETRY",
                        "OFFICIAL_BOUNDARY",
                        "fixture",
                        "fixture"
                )
        );

        Set<String> aliases = StopAreaAliasBuilder.build(List.of(), areas, cities)
                .aliases()
                .stream()
                .map(StopAreaAlias::aliasNormalized)
                .collect(Collectors.toSet());

        assertContains(aliases, "mainz lerchenberg hindemithstr");
        assertContains(aliases, "mainz lerchenberg hindemithstrasse");
    }

    private static void sqliteContractIsWrittenAndValidated() {
        try {
            Path testDir = Path.of("target", "self-test");
            Files.createDirectories(testDir);
            Path zip = testDir.resolve("contract-test.zip");
            Path database = testDir.resolve("contract-test.sqlite");
            Path contractJson = testDir.resolve("ixit_gtfs_contract_report.json");
            Files.deleteIfExists(zip);
            Files.deleteIfExists(database);
            Files.deleteIfExists(contractJson);

            writeGtfsZip(zip);
            PreprocessReport report = new GtfsPreprocessor().run(zip, database);
            String consoleText = report.toConsoleText();
            assertTrue(consoleText.contains("HubProfiles:"), "Expected HubProfiles section in PreprocessReport");
            assertTrue(consoleText.contains("RouteAxes:"), "Expected RouteAxes section in PreprocessReport");
            assertTrue(consoleText.contains("TransferRules:"), "Expected TransferRules section in PreprocessReport");
            assertTrue(consoleText.contains("Routing Compatibility Audit:"), "Expected Routing Compatibility Audit section in PreprocessReport");
            assertTrue(consoleText.contains("CalendarDateRows: 2"), "Expected calendar_dates row count in PreprocessReport");
            assertTrue(consoleText.contains("SQLite Diagnostics:"), "Expected SQLite Diagnostics section in PreprocessReport");
            assertTrue(consoleText.contains("App-Ready SQLite:"), "Expected App-Ready SQLite section in PreprocessReport");
            assertTrue(consoleText.contains("Display Name Audit:"), "Expected Display Name Audit section in PreprocessReport");
            assertTrue(consoleText.contains("Service Day Model:"), "Expected Service Day Model section in PreprocessReport");
            assertTrue(consoleText.contains("app_ready: true"), "Expected app-ready runtime DB in full mode");
            assertTrue(consoleText.contains("stop_times_rows_per_second"), "Expected stop_times throughput in PreprocessReport");
            assertTrue(consoleText.contains("sqlite_pragmas"), "Expected SQLite pragmas in PreprocessReport");
            assertTrue(consoleText.contains("Performance:"), "Expected Performance section in PreprocessReport");
            assertTrue(consoleText.contains("before_stop_search_tokens_release_mb"), "Expected SearchToken release snapshot");
            assertTrue(consoleText.contains("after_stop_search_tokens_release_mb"), "Expected compacted SearchToken heap snapshot");
            assertTrue(consoleText.contains("before_stop_times_mb"), "Expected stop_times memory snapshot");
            assertTrue(consoleText.contains("after_route_axes_mb"), "Expected RouteAxis memory snapshot");
            assertTrue(consoleText.contains("before_import_model_release_mb"), "Expected import model release snapshot");
            assertTrue(consoleText.contains("after_import_model_release_mb"), "Expected compacted import heap snapshot");
            assertTrue(consoleText.contains("RowCounts:"), "Expected RowCounts section in PreprocessReport");
            assertTrue(consoleText.contains("Warning Summary:"), "Expected Warning Summary section in PreprocessReport");
            assertTrue(consoleText.contains("Index Smoke Checks:"), "Expected Index Smoke Checks section in PreprocessReport");
            assertTrue(consoleText.contains("search_tokens_policy"), "Expected search token audit item");
            assertTrue(consoleText.contains("calendar_service_binding"), "Expected calendar audit item");
            assertTrue(consoleText.contains("calendar_dates_service_exceptions"), "Expected calendar_dates audit item");
            assertTrue(consoleText.contains("GTFS transfers without StopArea mapping"), "Expected unmapped transfer warning");
            assertEquals(SqliteContract.PREPROCESSOR_VERSION, report.realFeedValidationReport().preprocessorVersion());
            assertEquals(SqliteContract.CONTRACT_VERSION, report.realFeedValidationReport().contractVersion());
            assertEquals(SqliteContract.PREPROCESSOR_VERSION, report.realFeedValidationReport().validationVersion());
            assertEquals("FULL", report.sqliteDiagnostics().runMode());
            assertEquals(false, report.sqliteDiagnostics().derivedBuildersSkipped());
            assertTrue(report.sqliteDiagnostics().batchSize() >= 100, "Expected reported SQLite batch size");
            assertTrue(report.sqliteDiagnostics().stopTimesCommitRows() >= report.sqliteDiagnostics().batchSize(), "Expected reported commit row size");
            assertEquals(9L, report.sqliteDiagnostics().stopTimesRows());
            assertTrue(report.sqliteDiagnostics().sqlitePragmas().containsKey("journal_mode"), "Expected journal_mode pragma");
            assertTrue(report.sqliteDiagnostics().sqlitePragmas().containsKey("synchronous"), "Expected synchronous pragma");
            assertTrue(report.performanceReport().totalMs() >= 0, "Expected total runtime in performance report");
            assertTrue(report.realFeedValidationReport().sqliteFileSizeBytes() > 0, "Expected SQLite file size in validation report");
            assertTrue(report.realFeedValidationReport().tableRowCounts().containsKey("stop_times"), "Expected stop_times row count");
            assertTrue(report.indexSmokeChecks().values().stream().allMatch("OK"::equals), "Expected all index smoke checks to pass");
            assertTrue(report.appReadySqliteReport().appReady(), "Expected full mode SQLite to be app-ready");
            assertTrue(report.appReadySqliteReport().qualityChecks().get("stop_area_profiles_nonempty_line_labels") > 0, "Expected line labels in stop_area_profiles");
            assertTrue(report.appReadySqliteReport().qualityChecks().get("canonical_stop_areas_count") > 0, "Expected canonical_stop_areas rows");
            assertTrue(report.appReadySqliteReport().qualityChecks().get("canonical_stop_area_names_count") > 0, "Expected canonical_stop_area_names rows");
            assertTrue(report.appReadySqliteReport().qualityChecks().get("stop_area_display_names_count") > 0, "Expected stop_area_display_names rows");
            assertTrue(report.appReadySqliteReport().displayNameAudit().pass(), "Expected clean display-name audit");
            assertEquals(0L, report.appReadySqliteReport().displayNameAudit().residualCount());
            assertTrue(report.appReadySqliteReport().displayNameQualityBaseline().pass(),
                    "Expected complete non-destructive display quality baseline");
            assertEquals(0L, report.appReadySqliteReport().displayNameQualityBaseline().destructiveActionCount());
            assertTrue(report.serviceDayModelReport().pass(), "Expected valid combined service-day model");
            assertEquals(0L, report.serviceDayModelReport().unresolvedTripServiceCount());
            assertEquals(3L, report.serviceDayModelReport().overflowStopTimeCount());
            assertEquals(90_630L, report.serviceDayModelReport().maximumServiceDaySeconds());

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                assertEquals(SqliteContract.SCHEMA_VERSION, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'schema_version'"));
                assertEquals(SqliteContract.PREPROCESSOR_VERSION, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'preprocessor_version'"));
                assertEquals(SqliteContract.CONTRACT_VERSION, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'contract_version'"));
                assertEquals(SqliteContract.TIME_MODEL, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'time_model'"));
                assertEquals(SqliteContract.DISPLAY_NAME_TRANSFORMATION_VERSION,
                        queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'display_name_transformation_version'"));
                assertEquals(SqliteContract.DISPLAY_NAME_TRANSFORMATION_POLICY,
                        queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'display_name_transformation_policy'"));
                assertEquals("1", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'service_day_model_version'"));
                assertEquals(SqliteContract.SERVICE_DAY_RESOLUTION_POLICY, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'service_day_resolution_policy'"));
                assertEquals(SqliteContract.SERVICE_DAY_TIMEZONE_POLICY, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'service_day_timezone_policy'"));
                assertEquals(SqliteContract.SERVICE_DAY_TIME_OVERFLOW_POLICY, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'service_day_time_overflow_policy'"));
                assertEquals("Europe/Berlin", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'feed_timezones'"));
                assertEquals(SqliteContract.STOP_ID_POLICY, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'stop_id_policy'"));
                assertEquals("FULL", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'run_mode'"));
                assertTrue(queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'build_identity_sha256'").matches("[0-9a-f]{64}"), "Expected build identity SHA-256 metadata");
                assertTrue(queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'source_gtfs_sha256'").matches("[0-9a-f]{64}"), "Expected source GTFS SHA-256 metadata");
                assertTrue(queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'preprocessor_artifact_sha256'").matches("[0-9a-f]{64}"), "Expected preprocessor artifact SHA-256 metadata");
                assertEquals("GTFS", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'source_format'"));
                assertEquals("IXIT Test Feed", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'feed_name'"));
                assertEquals("unknown", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'feed_region'"));
                assertTrue(Long.parseLong(queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'source_file_size'")) > 0, "Expected source_file_size metadata");
                assertEquals("5", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'stop_count'"));
                assertEquals("3", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'stop_area_count'"));
                assertEquals("4", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'trip_count'"));
                assertEquals("9", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'stop_time_count'"));
                assertEquals("9", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'shape_point_count'"));
                assertEquals("2", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'calendar_count'"));
                assertEquals("2", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'calendar_dates_count'"));
                assertEquals("2", queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'route_count'"));

                for (String table : SqliteContract.EXPECTED_TABLES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'"));
                }
                for (String table : SqliteContract.ADDITIVE_TABLES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'"));
                }
                for (String index : SqliteContract.EXPECTED_INDEXES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
                }
                for (String index : SqliteContract.ADDITIVE_INDEXES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
                }

                assertEquals(88_200, queryInt(connection, "SELECT MAX(arrival_seconds) FROM stop_times WHERE stop_id = 'S1'"));
                assertEquals(90_600, queryInt(connection, "SELECT MAX(arrival_seconds) FROM stop_times WHERE stop_id = 'S2'"));
                assertEquals("SH1", queryString(connection, "SELECT shape_id FROM trips WHERE trip_id = 'T1'"));
                assertEquals(10, queryInt(connection, "SELECT shape_dist_traveled FROM stop_times WHERE trip_id = 'T1' AND stop_sequence = 2"));
                assertEquals(9, queryInt(connection, "SELECT COUNT(*) FROM shapes"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_search_tokens") > 0, "Expected stop_search_tokens rows");
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'display_name_quality_findings'"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_display_name_quality_classification'"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM display_name_quality_findings WHERE action <> 'PRESERVE'"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_area_aliases") > 0, "Expected stop_area_aliases rows");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_area_aliases WHERE area_id = 'S_PARENT' AND alias_normalized = 'dortmund hauptbahnhof' AND alias_type = 'STATION_SYNONYM'") > 0, "Expected Dortmund Hauptbahnhof alias");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_area_aliases WHERE area_id = 'S_PARENT' AND alias_normalized = 'dortmund bahnhof' AND alias_type = 'RAIL_STATION_INTENT'") > 0, "Expected Dortmund Bahnhof station-intent alias");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_area_aliases WHERE area_id = 'S_PARENT' AND alias_normalized = 'dortmund bf' AND alias_type = 'RAIL_STATION_INTENT'") > 0, "Expected Dortmund Bf station-intent alias");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_area_aliases WHERE area_id = 'S_PARENT' AND alias_normalized = 'do hoerde bahnhof' AND alias_type = 'STATION_SYNONYM'") > 0, "Expected DO-Hoerde Bahnhof alias");
                assertEquals(3, queryInt(connection, "SELECT COUNT(*) FROM stop_area_members WHERE area_id = 'S_PARENT'"));
                assertEquals(3, queryInt(connection, "SELECT COUNT(*) FROM area_route_service_summary"));
                assertEquals(5, queryInt(connection, "SELECT stop_time_count FROM area_route_service_summary WHERE area_id = 'S_PARENT' AND route_id = 'R1'"));
                assertEquals(3, queryInt(connection, "SELECT trip_count FROM area_route_service_summary WHERE area_id = 'S_PARENT' AND route_id = 'R1'"));
                assertEquals(0, queryInt(connection, "SELECT route_type FROM area_route_service_summary WHERE area_id = 'S_PARENT' AND route_id = 'R1'"));
                assertEquals(3, queryInt(connection, "SELECT COUNT(*) FROM stop_area_profiles"));
                assertEquals("URBAN_RAIL", queryString(connection, "SELECT profile_class FROM stop_area_profiles WHERE area_id = 'S_PARENT'"));
                assertEquals(1, queryInt(connection, "SELECT main_station_signal FROM stop_area_profiles WHERE area_id = 'S_PARENT'"));
                assertEquals(1, queryInt(connection, "SELECT has_tram FROM stop_area_profiles WHERE area_id = 'S_PARENT'"));
                assertEquals("NO_SERVICE", queryString(connection, "SELECT profile_class FROM stop_area_profiles WHERE area_id = 'S3'"));
                assertTrue(queryInt(connection, "SELECT search_priority_score FROM stop_area_profiles WHERE area_id = 'S_PARENT'") > queryInt(connection, "SELECT search_priority_score FROM stop_area_profiles WHERE area_id = 'S3'"), "Expected active station profile to outrank no-service area");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_areas") > 0, "Expected canonical_stop_areas rows");
                assertEquals("Dortmund Hbf", queryString(connection, "SELECT canonical_display_name FROM canonical_stop_areas WHERE primary_stop_area_id = 'S_PARENT'"));
                assertEquals("Dortmund Hbf", queryString(connection, "SELECT display_name FROM canonical_stop_area_names WHERE canonical_area_id = 'CAN_S_PARENT'"));
                assertEquals("Hauptbahnhof, Dortmund", queryString(connection, "SELECT public_display_name FROM stop_area_display_names WHERE area_id = 'S_PARENT'"));
                assertEquals("Dortmund", queryString(connection, "SELECT public_city_name FROM stop_area_display_names WHERE area_id = 'S_PARENT'"));
                assertTrue(queryString(connection, "SELECT explanation FROM stop_area_display_names WHERE area_id = 'S_PARENT'")
                                .contains("rules=CITY_FROM_RESOLVED_CONTEXT"),
                        "Expected machine-readable display transformation rules");
                assertEquals("URBAN_RAIL", queryString(connection, "SELECT profile_class FROM canonical_stop_areas WHERE primary_stop_area_id = 'S_PARENT'"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_area_members WHERE canonical_area_id = 'CAN_S_PARENT' AND area_id = 'S_PARENT' AND member_role = 'PRIMARY_RAIL'"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM (SELECT canonical_area_id FROM canonical_stop_area_members GROUP BY canonical_area_id HAVING SUM(is_primary_for_search) <> 1 OR SUM(is_visible_suggestion) <> 1)"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM hub_profiles") > 0, "Expected hub_profiles rows");
                assertEquals("MAIN_STATION_CANDIDATE", queryString(connection, "SELECT hub_level FROM hub_profiles WHERE area_id = 'S_PARENT'"));
                assertEquals(1, queryInt(connection, "SELECT has_main_station_keyword FROM hub_profiles WHERE area_id = 'S_PARENT'"));
                assertEquals("MEDIUM", queryString(connection, "SELECT hub_level FROM hub_profiles WHERE area_id = 'S2'"));
                assertEquals(2, queryInt(connection, "SELECT route_count FROM hub_profiles WHERE area_id = 'S2'"));
                assertTrue(queryInt(connection, "SELECT transfer_candidate_score FROM hub_profiles WHERE area_id = 'S2'") > 0, "Expected positive hub score");
                assertEquals(3, queryInt(connection, "SELECT COUNT(*) FROM route_axes"));
                assertEquals(2, queryInt(connection, "SELECT trip_count FROM route_axes WHERE route_id = 'R1' AND direction_id = '0'"));
                assertEquals(1, queryInt(connection, "SELECT trip_count FROM route_axes WHERE route_id = 'R1' AND direction_id = '1'"));
                assertEquals(2, queryInt(connection, "SELECT stop_count FROM route_axes WHERE route_id = 'R1' AND direction_id = '0'"));
                assertEquals(2, queryInt(connection, "SELECT COUNT(*) FROM route_axis_stops WHERE axis_id = (SELECT axis_id FROM route_axes WHERE route_id = 'R1' AND direction_id = '0')"));
                assertEquals("S_PARENT,S2", queryString(connection, "SELECT group_concat(area_id, ',') FROM (SELECT area_id FROM route_axis_stops WHERE axis_id = (SELECT axis_id FROM route_axes WHERE route_id = 'R1' AND direction_id = '0') ORDER BY sequence_index)"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM transfer_rules") > 0, "Expected transfer_rules rows");
                assertEquals(5, queryInt(connection, "SELECT COUNT(*) FROM transfers"));
                assertEquals("T1", queryString(connection, "SELECT from_trip_id FROM transfers WHERE transfer_type = 4"));
                assertEquals("WKD", queryString(connection, "SELECT service_id FROM transfers WHERE transfer_type = 4"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_rules WHERE source = 'GTFS_TRANSFERS' AND from_area_id = 'S_PARENT' AND to_area_id = 'S2' AND from_stop_id = 'S1' AND to_stop_id = 'S2' AND min_transfer_time_seconds = 180 AND transfer_semantic = 'MINIMUM_TIME' AND scope_type = 'STOP' AND pedestrian_usable = 1"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_rules WHERE transfer_semantic = 'IN_SEAT_ALLOWED' AND scope_type = 'TRIP_SERVICE' AND pedestrian_usable = 0"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_rules WHERE transfer_semantic = 'PROHIBITED' AND pedestrian_usable = 0"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_rules WHERE source = 'SAME_STOP_AREA' AND from_area_id = 'S_PARENT' AND to_area_id = 'S_PARENT'"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_rules WHERE source = 'SAME_STOP_AREA' AND confidence = 'LOW' AND pedestrian_usable = 0"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM transfer_edges") > 0, "Expected transfer_edges rows");
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges WHERE source = 'GTFS_TRANSFERS' AND from_stop_area_id = 'S_PARENT' AND to_stop_area_id = 'S2' AND from_stop_id = 'S1' AND to_stop_id = 'S2' AND min_transfer_seconds = 180 AND min_transfer_minutes = 3 AND quality = 'GOOD' AND is_traversable = 1 AND edge_kind = 'GTFS_PEDESTRIAN_TRANSFER'"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges edge JOIN transfers raw ON raw.transfer_id=edge.raw_transfer_id WHERE raw.transfer_type IN (3,4,5)"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges WHERE source = 'SAME_STOP_AREA' AND from_stop_area_id = 'S_PARENT' AND to_stop_area_id = 'S_PARENT' AND is_traversable = 0 AND edge_kind = 'AREA_MEMBERSHIP_CANDIDATE' AND distance_meters > 0"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges WHERE source = 'DISTANCE_HEURISTIC' AND from_stop_area_id = 'S2' AND to_stop_area_id = 'S3'"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges WHERE source = 'DISTANCE_HEURISTIC' AND from_stop_area_id = 'S3' AND to_stop_area_id = 'S2' AND min_transfer_minutes = 2 AND quality = 'CANDIDATE' AND is_traversable = 0"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges WHERE source = 'DISTANCE_HEURISTIC' AND from_stop_area_id = 'S_PARENT' AND to_stop_area_id = 'S3'"));
                assertEquals(2, queryInt(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE area_id = 'S_PARENT'"));
                assertEquals(2, queryInt(connection, "SELECT COUNT(*) FROM stop_footpaths WHERE area_id = 'S_PARENT' AND is_traversable = 1 AND min_transfer_seconds >= 120 AND distance_model = 'STRAIGHT_LINE_LOWER_BOUND'"));
                assertEquals(2, queryInt(connection, "SELECT COUNT(*) FROM calendar_dates"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM calendar_dates WHERE service_id = 'WKD_EXTRA' AND date = '20260102' AND exception_type = 1"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM calendar_dates WHERE service_id = 'WKD' AND date = '20260103' AND exception_type = 2"));
                assertEquals("ADDITION", queryString(connection, "SELECT exception_action FROM calendar_dates WHERE service_id = 'WKD_EXTRA' AND date = '20260102'"));
                assertEquals("REMOVAL", queryString(connection, "SELECT exception_action FROM calendar_dates WHERE service_id = 'WKD' AND date = '20260103'"));
                assertEquals("BASE_WITH_EXCEPTIONS", queryString(connection, "SELECT status FROM service_calendar_summary WHERE service_id = 'WKD'"));
                assertEquals(4, queryInt(connection, "SELECT trip_count FROM service_calendar_summary WHERE service_id = 'WKD'"));
                assertEquals("Europe/Berlin", queryString(connection, "SELECT service_timezone FROM service_calendar_summary WHERE service_id = 'WKD'"));

                var activeService = ServiceDayResolver.resolve(connection, "WKD", LocalDate.of(2026, 1, 2));
                assertTrue(activeService.active(), "Expected WKD trips to be active on Friday");
                assertEquals(4L, activeService.activeTripCount());
                var removedService = ServiceDayResolver.resolve(connection, "WKD", LocalDate.of(2026, 1, 3));
                assertEquals(false, removedService.active());
                assertEquals("CALENDAR_DATES_REMOVAL", removedService.reason());
            }

            assertTrue(Files.isRegularFile(contractJson), "Expected JSON contract report");
            String contractJsonText = Files.readString(contractJson, StandardCharsets.UTF_8);
            assertTrue(contractJsonText.contains("\"routing_compatibility_audit\""), "Expected routing compatibility audit JSON");
            assertTrue(contractJsonText.contains("\"audit_version\": \"" + SqliteContract.PREPROCESSOR_VERSION + "\""), "Expected current audit version in JSON");
            assertTrue(contractJsonText.contains("\"real_feed_validation\""), "Expected real feed validation JSON");
            assertTrue(contractJsonText.contains("\"validation_version\": \"" + SqliteContract.PREPROCESSOR_VERSION + "\""), "Expected validation version in JSON");
            assertTrue(contractJsonText.contains("\"preprocessor_version\": \"" + SqliteContract.PREPROCESSOR_VERSION + "\""), "Expected preprocessor version in JSON");
            assertTrue(contractJsonText.contains("\"contract_version\": \"" + SqliteContract.CONTRACT_VERSION + "\""), "Expected contract version in JSON");
            assertTrue(contractJsonText.contains("\"build_identity_sha256\""), "Expected build identity in JSON metadata");
            assertTrue(contractJsonText.contains("\"metadata\""), "Expected metadata block in JSON");
            assertTrue(contractJsonText.contains("\"feed_name\": \"IXIT Test Feed\""), "Expected feed_name metadata in JSON");
            assertTrue(contractJsonText.contains("\"stop_time_count\": \"9\""), "Expected stop_time_count metadata in JSON");
            assertTrue(contractJsonText.contains("\"transfer_edges\""), "Expected transfer_edges block in JSON");
            assertTrue(contractJsonText.contains("\"transfer_footpath_audit\""), "Expected transfer-footpath audit in JSON");
            assertTrue(contractJsonText.contains("\"non_pedestrian_gtfs_edges\": 0"), "Expected non-walking GTFS transfers to stay out of pedestrian edges");
            assertTrue(contractJsonText.contains("\"stop_area_aliases\""), "Expected stop_area_aliases row count in JSON");
            assertTrue(contractJsonText.contains("\"area_route_service_summary\": 3"), "Expected area_route_service_summary row count in JSON");
            assertTrue(contractJsonText.contains("\"stop_area_profiles\": 3"), "Expected stop_area_profiles row count in JSON");
            assertTrue(contractJsonText.contains("\"canonical_stop_areas\""), "Expected canonical_stop_areas row count in JSON");
            assertTrue(contractJsonText.contains("\"canonical_stop_area_names\""), "Expected canonical_stop_area_names row count in JSON");
            assertTrue(contractJsonText.contains("\"edge_count\""), "Expected transfer edge count in JSON");
            assertTrue(contractJsonText.contains("\"DISTANCE_HEURISTIC\""), "Expected transfer edge source counts in JSON");
            assertTrue(contractJsonText.contains("\"calendar_dates\": 2"), "Expected calendar_dates row count in JSON");
            assertTrue(contractJsonText.contains("\"sqlite_diagnostics\""), "Expected SQLite diagnostics in JSON");
            assertTrue(contractJsonText.contains("\"batch_size\""), "Expected SQLite batch size in JSON");
            assertTrue(contractJsonText.contains("\"sqlite_pragmas\""), "Expected SQLite pragmas in JSON");
            assertTrue(contractJsonText.contains("\"performance_sections\""), "Expected performance sections in JSON");
            assertTrue(contractJsonText.contains("\"memory_snapshots_mb\""), "Expected memory snapshots in JSON");
            assertTrue(contractJsonText.contains("\"index_smoke_checks\""), "Expected index smoke checks in JSON");
            assertTrue(contractJsonText.contains("\"app_ready_sqlite\""), "Expected app-ready SQLite report in JSON");
            assertTrue(contractJsonText.contains("\"app_ready\": true"), "Expected app-ready true in JSON");
            assertTrue(contractJsonText.contains("\"display_name_quality_baseline\""), "Expected display quality baseline in JSON");
            assertTrue(contractJsonText.contains("\"service_day_model\""), "Expected service day model in JSON");
            assertTrue(contractJsonText.contains("\"display_name_audit\""), "Expected display-name audit JSON");
            assertTrue(contractJsonText.contains("\"matching_city_code_prefixes\": 0"), "Expected no known city-prefix residue");
            assertTrue(contractJsonText.contains("\"invalid_transformation_rule_rows\": 0"),
                    "Expected valid display transformation protocol");
            assertTrue(contractJsonText.contains("\"transformation_rule_counts\""),
                    "Expected transformation rule counts in JSON");

            assertRoutingContractConsumerPoc(database);
            assertRoutingContractRealFeedAudit(database);

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE transfer_edges
                        SET is_traversable = 1
                        WHERE edge_kind = 'AREA_MEMBERSHIP_CANDIDATE'
                        """);
            }
            var failedTransferAudit = TransferFootpathAuditor.audit(database);
            assertEquals(false, failedTransferAudit.pass());
            assertEquals(1L, failedTransferAudit.traversableAreaMembershipEdges());
            try {
                SqliteContractValidator.validate(database);
                throw new AssertionError("Expected traversable area membership to break the SQLite contract");
            } catch (IllegalStateException expected) {
                assertTrue(
                        expected.getMessage().contains("traversable_area_membership=1"),
                        "Expected clear transfer-footpath contract error"
                );
            }
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE transfer_edges
                        SET is_traversable = 0
                        WHERE edge_kind = 'AREA_MEMBERSHIP_CANDIDATE'
                        """);
            }
            assertTrue(TransferFootpathAuditor.audit(database).pass(),
                    "Expected restored transfer graph to pass the semantic audit");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE stop_area_display_names
                        SET public_stop_name = 'DO-Huckarde S',
                            public_display_name = 'DO-Huckarde S, Dortmund'
                        WHERE area_id = 'S_PARENT'
                        """);
            }
            var failedDisplayAudit = AppReadySqliteValidator.validate(database);
            assertEquals(false, failedDisplayAudit.appReady());
            assertEquals(1L, failedDisplayAudit.displayNameAudit().matchingCityCodePrefixes());
            assertTrue(
                    failedDisplayAudit.warnings().stream().anyMatch(warning -> warning.contains("Display-name contract violations")),
                    "Expected display-name audit to gate APP_RUNTIME"
            );

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("""
                        UPDATE stop_area_display_names
                        SET public_stop_name = 'Dortmund',
                            public_display_name = 'Dortmund, Dortmund'
                        WHERE area_id = 'S_PARENT'
                        """);
                statement.executeUpdate("""
                        INSERT INTO display_name_quality_findings(
                            area_id, finding_type, classification, prefix,
                            public_stop_name, public_city_name, public_display_name, action, rationale
                        ) VALUES (
                            'S_PARENT', 'MUNICIPALITY_ONLY', 'LOCALITY_STOP_NAME', '',
                            'Dortmund', 'Dortmund', 'Dortmund, Dortmund', 'PRESERVE',
                            'Reviewed fixture: the locality name is retained without inventing a station suffix.'
                        )
                        """);
            }
            var municipalityOnlyAudit = AppReadySqliteValidator.validate(database);
            assertTrue(municipalityOnlyAudit.appReady(), "Municipality-only names must remain diagnostic");
            assertEquals(1L, municipalityOnlyAudit.displayNameAudit().municipalityOnlyNames());
            assertEquals(0L, municipalityOnlyAudit.displayNameAudit().duplicateCityNamePrefixes());
            assertEquals(1L, municipalityOnlyAudit.displayNameQualityBaseline().municipalityOnlyFindingCount());
            assertEquals(0L, municipalityOnlyAudit.displayNameQualityBaseline().destructiveActionCount());

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ixit_metadata SET value = 'invalid' WHERE key = 'build_identity_sha256'");
            }
            try {
                SqliteContractValidator.validate(database);
                throw new AssertionError("Expected invalid build identity to break the SQLite contract");
            } catch (IllegalStateException expected) {
                assertTrue(
                        expected.getMessage().contains("Invalid SHA-256 ixit_metadata value: build_identity_sha256"),
                        "Expected clear build identity contract error"
                );
            }
        } catch (IOException | SQLException ex) {
            throw new AssertionError("SQLite contract self-test failed", ex);
        }
    }

    private static void assertRoutingContractConsumerPoc(Path database) throws SQLException {
        var report = RoutingContractConsumerPoc.inspect(
                database,
                LocalDate.of(2026, 1, 2),
                "S_PARENT",
                "S2",
                GtfsTimeParser.toSecondsSinceServiceDayStart("24:00:00"),
                GtfsTimeParser.toSecondsSinceServiceDayStart("26:00:00"),
                10
        );
        assertTrue(report.pass(), "Expected Routing Contract Consumer PoC to validate fixture: " + report.failures());
        assertEquals(SqliteContract.CONTRACT_VERSION, report.contract().contractVersion());
        assertEquals(3, report.startArea().concreteMembers().size());
        assertEquals(1, report.targetArea().concreteMembers().size());
        assertTrue(report.overflowTimeObserved(), "Expected a validated trip after 24:00:00");
        assertTrue(!report.validatedLegs().isEmpty(), "Expected concrete direct-trip evidence");
        var firstLeg = report.validatedLegs().get(0);
        assertEquals("T1", firstLeg.tripId());
        assertEquals("S1", firstLeg.startStopId());
        assertEquals("S2", firstLeg.targetStopId());
        assertEquals(88_260, firstLeg.departureSeconds());
        assertEquals(90_600, firstLeg.arrivalSeconds());
        assertEquals("24:31:00", firstLeg.departureTime());
        assertEquals("25:10:00", firstLeg.arrivalTime());
        assertTrue(firstLeg.serviceDayValid(), "Expected active WKD service on Friday");
        assertTrue(firstLeg.stopTimePathValid(), "Expected concrete trip/stop_times path validation");

        var removedServiceReport = RoutingContractConsumerPoc.inspect(
                database,
                LocalDate.of(2026, 1, 3),
                "S_PARENT",
                "S2",
                GtfsTimeParser.toSecondsSinceServiceDayStart("24:00:00"),
                GtfsTimeParser.toSecondsSinceServiceDayStart("26:00:00"),
                10
        );
        assertEquals(false, removedServiceReport.pass());
        assertTrue(
                removedServiceReport.failures().stream().anyMatch(failure -> failure.contains("No concrete trip")),
                "Expected calendar_dates removal to suppress the trip"
        );

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE ixit_metadata SET value = '9.9' WHERE key = 'contract_version'");
        }
        try {
            assertThrows(
                    RoutingContractConsumerPoc.RoutingContractViolationException.class,
                    () -> RoutingContractConsumerPoc.inspect(
                            database,
                            LocalDate.of(2026, 1, 2),
                            "S_PARENT",
                            "S2",
                            0,
                            GtfsTimeParser.toSecondsSinceServiceDayStart("27:00:00"),
                            10
                    )
            );
        } finally {
            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE ixit_metadata SET value = '" + SqliteContract.CONTRACT_VERSION + "' WHERE key = 'contract_version'");
            }
        }
    }

    private static void assertRoutingContractRealFeedAudit(Path database) {
        try {
            Path scenarios = Path.of("target", "self-test", "routing-contract-real-feed-scenarios.json");
            Files.writeString(scenarios, """
                    {
                      "scenarioVersion": "0.8.1",
                      "scenarios": [
                        {
                          "id": "dortmund-overflow",
                          "city": "Dortmund",
                          "serviceDate": "2026-01-02",
                          "startAreaId": "S_PARENT",
                          "targetAreaId": "S_PARENT",
                          "fromTime": "24:00:00",
                          "toTime": "26:00:00",
                          "limit": 10
                        }
                      ]
                    }
                    """, StandardCharsets.UTF_8);

            String fixtureHash = "a".repeat(64);
            var report = RoutingContractRealFeedAuditor.audit(
                    database,
                    scenarios,
                    "self-test fixture copied into the isolated tool target directory",
                    Map.of("FIXTURE", fixtureHash),
                    List.of("Dortmund"),
                    60_000,
                    10
            );
            assertTrue(report.pass(), "Expected v0.8.1 real-feed audit fixture to pass: " + report.failures());
            assertEquals(1, report.scenarios().size());
            assertEquals("Hauptbahnhof, Dortmund", report.scenarios().get(0).startDisplayName().publicDisplayName());
            assertTrue(report.scenarios().get(0).consumer().overflowTimeObserved(), "Expected scenario overflow evidence");
            assertTrue(report.serviceDayExceptions().additionsObserved(), "Expected calendar_dates addition evidence");
            assertTrue(report.serviceDayExceptions().removalsObserved(), "Expected calendar_dates removal evidence");
            assertTrue(report.overflowTimes().overflowRowCount() > 0, "Expected database-wide overflow evidence");

            var missingCity = RoutingContractRealFeedAuditor.audit(
                    database,
                    scenarios,
                    "self-test fixture copied into the isolated tool target directory",
                    Map.of("FIXTURE", fixtureHash),
                    List.of("Berlin"),
                    60_000,
                    10
            );
            assertEquals(false, missingCity.pass());
            assertTrue(
                    missingCity.failures().stream().anyMatch(failure -> failure.contains("required city")),
                    "Expected missing required-city failure"
            );
        } catch (IOException | SQLException exception) {
            throw new AssertionError("Routing Contract Real Feed Audit self-test failed", exception);
        }
    }

    private static void coreOnlyModeSkipsDerivedBuildersButKeepsContract() {
        try {
            Path testDir = Path.of("target", "self-test");
            Files.createDirectories(testDir);
            Path zip = testDir.resolve("core-only-test.zip");
            Path database = testDir.resolve("core-only-test.sqlite");
            Path contractJson = testDir.resolve("core-only-contract-report.json");
            Files.deleteIfExists(zip);
            Files.deleteIfExists(database);
            Files.deleteIfExists(contractJson);

            writeGtfsZip(zip);
            PreprocessReport report = new GtfsPreprocessor().run(zip, database, contractJson, PreprocessOptions.stressCoreOnly());
            String consoleText = report.toConsoleText();

            assertEquals("CORE_ONLY", report.sqliteDiagnostics().runMode());
            assertEquals(true, report.sqliteDiagnostics().derivedBuildersSkipped());
            assertTrue(report.sqliteDiagnostics().skippedDerivedBuilders().contains("route_axes"), "Expected RouteAxis skip marker");
            assertTrue(consoleText.contains("derived_builders_skipped: true"), "Expected derived skip marker in console report");
            assertTrue(consoleText.contains("Diagnostic mode CORE_ONLY skipped derived builders"), "Expected core-only warning");
            assertEquals(SqliteContract.CONTRACT_VERSION, report.realFeedValidationReport().contractVersion());
            assertEquals(false, report.appReadySqliteReport().appReady());
            assertTrue(report.appReadySqliteReport().warnings().stream().anyMatch(warning -> warning.contains("stop_area_profiles")), "Expected stop_area_profiles app-ready warning");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                for (String table : SqliteContract.EXPECTED_TABLES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'"));
                }
                for (String table : SqliteContract.ADDITIVE_TABLES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'"));
                }
                for (String index : SqliteContract.EXPECTED_INDEXES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
                }
                for (String index : SqliteContract.ADDITIVE_INDEXES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
                }
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM hub_profiles"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_area_aliases") > 0, "Expected stop_area_aliases in CORE_ONLY mode");
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM area_route_service_summary"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM stop_area_profiles"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_areas"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_area_members"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_area_names"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM route_axes"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM transfer_rules"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM transfer_edges"));
                assertEquals(9, queryInt(connection, "SELECT COUNT(*) FROM stop_times"));
                assertEquals(2, queryInt(connection, "SELECT COUNT(*) FROM calendar_dates"));
            }

            String contractJsonText = Files.readString(contractJson, StandardCharsets.UTF_8);
            assertTrue(contractJsonText.contains("\"run_mode\": \"CORE_ONLY\""), "Expected CORE_ONLY mode in JSON diagnostics");
            assertTrue(contractJsonText.contains("\"derived_builders_skipped\": true"), "Expected derived skip flag in JSON diagnostics");
            assertTrue(contractJsonText.contains("\"app_ready\": false"), "Expected app-ready false in CORE_ONLY JSON");
        } catch (IOException | SQLException ex) {
            throw new AssertionError("Core-only self-test failed", ex);
        }
    }

    private static void appRuntimeModeRequiresAppReadySqlite() {
        try {
            Path testDir = Path.of("target", "self-test");
            Files.createDirectories(testDir);
            Path zip = testDir.resolve("app-runtime-test.zip");
            Path database = testDir.resolve("app-runtime-test.sqlite");
            Path contractJson = testDir.resolve("app-runtime-contract-report.json");
            Files.deleteIfExists(zip);
            Files.deleteIfExists(database);
            Files.deleteIfExists(contractJson);

            writeGtfsZip(zip);
            PreprocessReport report = new GtfsPreprocessor().run(zip, database, contractJson, PreprocessOptions.appRuntime());

            assertEquals("APP_RUNTIME", report.sqliteDiagnostics().runMode());
            assertEquals(false, report.sqliteDiagnostics().derivedBuildersSkipped());
            assertEquals(SqliteContract.APP_RUNTIME_CONTRACT_VERSION, report.realFeedValidationReport().contractVersion());
            assertTrue(report.appReadySqliteReport().appReady(), "Expected APP_RUNTIME to require app-ready SQLite");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                assertEquals(SqliteContract.APP_RUNTIME_CONTRACT_VERSION, queryString(connection, "SELECT value FROM ixit_metadata WHERE key = 'contract_version'"));
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM area_route_service_summary") > 0, "Expected APP_RUNTIME area_route_service_summary rows");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_areas") > 0, "Expected APP_RUNTIME canonical_stop_areas rows");
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM canonical_stop_area_names") > 0, "Expected APP_RUNTIME canonical_stop_area_names rows");
                for (String index : SqliteContract.APP_RUNTIME_EXPECTED_INDEXES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
                }
                for (String index : SqliteContract.APP_RUNTIME_ADDITIVE_INDEXES) {
                    assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = '" + index + "'"));
                }
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_route_axes_route_id'"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_stop_times_stop_trip'"));
            }

            String contractJsonText = Files.readString(contractJson, StandardCharsets.UTF_8);
            assertTrue(contractJsonText.contains("\"run_mode\": \"APP_RUNTIME\""), "Expected APP_RUNTIME mode in JSON diagnostics");
            assertTrue(contractJsonText.contains("\"contract_version\": \"" + SqliteContract.APP_RUNTIME_CONTRACT_VERSION + "\""), "Expected app-runtime contract version in JSON");
            assertTrue(contractJsonText.contains("\"app_ready\": true"), "Expected app-ready true in APP_RUNTIME JSON");
        } catch (IOException | SQLException ex) {
            throw new AssertionError("App-runtime self-test failed", ex);
        }
    }

    private static void mobileArtifactPackagingIsReducedSignedAndFailClosed() {
        try {
            Path testDir = Files.createTempDirectory("ixit-mobile-artifact-");
            Path zip = testDir.resolve("source.zip");
            Path sourceDatabase = testDir.resolve("source.sqlite");
            Path packageDirectory = testDir.resolve("package-v1");
            writeGtfsZip(zip);
            new GtfsPreprocessor().run(zip, sourceDatabase, null, PreprocessOptions.appRuntime());

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair signingKey = generator.generateKeyPair();
            MobileArtifactManifest manifest = MobileArtifactPackager.packageArtifact(
                    sourceDatabase,
                    packageDirectory,
                    List.of("S_PARENT"),
                    "dortmund-test-v1",
                    16L * 1024 * 1024,
                    signingKey.getPrivate(),
                    signingKey.getPublic()
            );

            assertEquals(MobileArtifactPackager.ARTIFACT_PROFILE, manifest.artifactProfile());
            assertEquals(3L, manifest.rowCounts().get("trips"));
            assertEquals(8L, manifest.rowCounts().get("stop_times"));
            assertTrue(manifest.databaseBytes() < Files.size(sourceDatabase),
                    "Expected corridor artifact to be smaller than APP_RUNTIME source");
            MobileArtifactManifest verified = MobileArtifactPackager.verifyPackage(
                    packageDirectory,
                    signingKey.getPublic(),
                    16L * 1024 * 1024
            );
            assertEquals(manifest.databaseSha256(), verified.databaseSha256());

            Path signature = packageDirectory.resolve(MobileArtifactPackager.SIGNATURE_FILE);
            String originalSignature = Files.readString(signature, StandardCharsets.US_ASCII);
            char replacement = originalSignature.charAt(0) == 'A' ? 'B' : 'A';
            Files.writeString(
                    signature,
                    replacement + originalSignature.substring(1),
                    StandardCharsets.US_ASCII
            );
            assertThrows(IllegalArgumentException.class, () -> MobileArtifactPackager.verifyPackage(
                    packageDirectory,
                    signingKey.getPublic(),
                    16L * 1024 * 1024
            ));
            Files.writeString(signature, originalSignature, StandardCharsets.US_ASCII);

            Path mobileDatabase = packageDirectory.resolve(MobileArtifactPackager.DATABASE_FILE);
            Files.write(mobileDatabase, new byte[]{0}, StandardOpenOption.APPEND);
            assertThrows(IllegalArgumentException.class, () -> MobileArtifactPackager.verifyPackage(
                    packageDirectory,
                    signingKey.getPublic(),
                    16L * 1024 * 1024
            ));

            Path unknownOutput = testDir.resolve("unknown-seed");
            assertThrows(IllegalArgumentException.class, () -> MobileArtifactPackager.packageArtifact(
                    sourceDatabase,
                    unknownOutput,
                    List.of("UNKNOWN_AREA"),
                    "unknown-seed-v1",
                    16L * 1024 * 1024,
                    signingKey.getPrivate(),
                    signingKey.getPublic()
            ));
            assertTrue(!Files.exists(unknownOutput), "Unknown seed must not publish an artifact");

            Path oversizedOutput = testDir.resolve("oversized");
            assertThrows(IllegalStateException.class, () -> MobileArtifactPackager.packageArtifact(
                    sourceDatabase,
                    oversizedOutput,
                    List.of("S_PARENT"),
                    "oversized-test-v1",
                    1,
                    signingKey.getPrivate(),
                    signingKey.getPublic()
            ));
            assertTrue(!Files.exists(oversizedOutput), "Oversized package must not be published");
        } catch (Exception exception) {
            throw new AssertionError("Mobile artifact packaging self-test failed", exception);
        }
    }

    private static void searchableMobileArtifactAndUpdateContractFailClosed() {
        try {
            Path testDir = Files.createTempDirectory("ixit-searchable-mobile-artifact-");
            Path zip = testDir.resolve("source.zip");
            Path sourceDatabase = testDir.resolve("source.sqlite");
            Path packageDirectory = testDir.resolve("package-v2");
            Path descriptorDirectory = testDir.resolve("update-v1");
            writeGtfsZip(zip);
            new GtfsPreprocessor().run(zip, sourceDatabase, null, PreprocessOptions.appRuntime());

            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair signingKey = generator.generateKeyPair();
            MobileArtifactManifest manifest = MobileArtifactPackager.packageSearchableArtifact(
                    sourceDatabase,
                    packageDirectory,
                    List.of("S_PARENT"),
                    "dortmund-search-test-v2",
                    16L * 1024 * 1024,
                    signingKey.getPrivate(),
                    signingKey.getPublic()
            );
            assertEquals(MobileArtifactPackager.SEARCHABLE_ARTIFACT_PROFILE,
                    manifest.artifactProfile());
            assertTrue(manifest.rowCounts().get("stop_search_tokens") > 0,
                    "Expected searchable package tokens");
            assertTrue(manifest.rowCounts().get("stop_area_display_names") > 0,
                    "Expected searchable package display names");
            MobileArtifactPackager.verifySearchablePackage(
                    packageDirectory,
                    signingKey.getPublic(),
                    16L * 1024 * 1024
            );

            try (Connection connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + packageDirectory.resolve(MobileArtifactPackager.DATABASE_FILE))) {
                assertTrue(queryInt(connection, "SELECT COUNT(*) FROM stop_search_tokens "
                        + "WHERE token IN ('dortmund', 'hauptbahnhof')") > 0,
                        "Expected Dortmund search vocabulary in reduced artifact");
                assertEquals(
                        queryInt(connection, "SELECT COUNT(*) FROM stop_areas"),
                        queryInt(connection, "SELECT COUNT(*) FROM stop_area_display_names")
                );
            }

            Instant publishedAt = Instant.parse("2026-08-18T08:00:00Z");
            Instant notBefore = Instant.parse("2026-08-18T08:00:00Z");
            Instant expiresAt = Instant.parse("2026-08-25T08:00:00Z");
            MobileArtifactUpdateDescriptor descriptor = MobileArtifactUpdateContract.create(
                    packageDirectory,
                    descriptorDirectory,
                    URI.create("https://updates.ixit.example/mobile/dortmund/"),
                    "pilot",
                    42,
                    publishedAt,
                    notBefore,
                    expiresAt,
                    16L * 1024 * 1024,
                    signingKey.getPrivate(),
                    signingKey.getPublic()
            );
            assertEquals(42L, descriptor.sequenceNumber());
            MobileArtifactUpdateContract.verifyDescriptor(
                    descriptorDirectory,
                    signingKey.getPublic(),
                    notBefore.plusSeconds(1),
                    "pilot",
                    41,
                    Set.of("updates.ixit.example")
            );
            MobileArtifactUpdateContract.verifyArtifactBinding(
                    descriptor,
                    packageDirectory,
                    signingKey.getPublic(),
                    16L * 1024 * 1024
            );
            assertThrows(IllegalArgumentException.class, () ->
                    MobileArtifactUpdateContract.verifyDescriptor(
                            descriptorDirectory,
                            signingKey.getPublic(),
                            notBefore.plusSeconds(1),
                            "pilot",
                            42,
                            Set.of("updates.ixit.example")
                    ));
            assertThrows(IllegalArgumentException.class, () ->
                    MobileArtifactUpdateContract.verifyDescriptor(
                            descriptorDirectory,
                            signingKey.getPublic(),
                            notBefore.plusSeconds(1),
                            "pilot",
                            41,
                            Set.of("download.evil.example")
                    ));

            Path signature = descriptorDirectory.resolve(MobileArtifactUpdateContract.SIGNATURE_FILE);
            String originalSignature = Files.readString(signature, StandardCharsets.US_ASCII);
            char replacement = originalSignature.charAt(0) == 'A' ? 'B' : 'A';
            Files.writeString(
                    signature,
                    replacement + originalSignature.substring(1),
                    StandardCharsets.US_ASCII
            );
            assertThrows(IllegalArgumentException.class, () ->
                    MobileArtifactUpdateContract.verifyDescriptor(
                            descriptorDirectory,
                            signingKey.getPublic(),
                            notBefore.plusSeconds(1),
                            "pilot",
                            41,
                            Set.of("updates.ixit.example")
                    ));
        } catch (Exception exception) {
            throw new AssertionError("Searchable mobile artifact self-test failed", exception);
        }
    }

    private static void missingCalendarDatesFileStillProducesValidContract() {
        try {
            Path testDir = Path.of("target", "self-test");
            Files.createDirectories(testDir);
            Path zip = testDir.resolve("missing-calendar-dates-test.zip");
            Path database = testDir.resolve("missing-calendar-dates-test.sqlite");
            Files.deleteIfExists(zip);
            Files.deleteIfExists(database);

            writeGtfsZip(zip, false);
            PreprocessReport report = new GtfsPreprocessor().run(zip, database);
            assertEquals(0L, report.calendarDateRows());
            assertTrue(report.warnings().contains("Optional GTFS file missing: calendar_dates.txt"), "Expected missing calendar_dates warning");

            try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'calendar_dates'"));
                assertEquals(1, queryInt(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name = 'idx_calendar_dates_service_date'"));
                assertEquals(0, queryInt(connection, "SELECT COUNT(*) FROM calendar_dates"));
            }
        } catch (IOException | SQLException ex) {
            throw new AssertionError("Missing calendar_dates self-test failed", ex);
        }
    }

    private static void writeGtfsZip(Path zip) throws IOException {
        writeGtfsZip(zip, true);
    }

    private static CanonicalStopArea canonicalArea(
            String canonicalAreaId,
            String canonicalDisplayName,
            String originalName,
            String cityName,
            String stationName
    ) {
        return new CanonicalStopArea(
                canonicalAreaId,
                canonicalDisplayName,
                originalName,
                cityName,
                stationName,
                cityName == null || cityName.isBlank() ? "STATION_ONLY" : "CITY_STATION",
                canonicalAreaId + "_PRIMARY",
                "TEST",
                true,
                "",
                1,
                "GOOD",
                "TEST",
                "test"
        );
    }

    private static StopAreaCity resolvedCity(String areaId, String cityName) {
        return new StopAreaCity(
                areaId,
                "fixture",
                cityName,
                "Stadt",
                "BKG_VG250_GEOMETRY",
                "OFFICIAL_BOUNDARY",
                "fixture",
                "fixture"
        );
    }

    private static void writeGtfsZip(Path zip, boolean includeCalendarDates) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(zip))) {
            writeEntry(output, "agency.txt", """
                    agency_id,agency_name,agency_url,agency_timezone
                    A1,IXIT Test Transit,https://example.invalid,Europe/Berlin
                    """);
            writeEntry(output, "stops.txt", """
                    stop_id,stop_name,stop_lat,stop_lon,parent_station,location_type,platform_code
                    S_PARENT,Dortmund Hbf,51.5136,7.4653,,1,
                    S1,DO-Hoerde Bf,51.4890,7.5000,S_PARENT,0,1
                    S1B,DO-Hoerde Bf Gleis 2,51.4891,7.5001,S_PARENT,0,2
                    S2,Kampstrasse U,51.5140,7.4630,,0,
                    S3,Kampstrasse Nord,51.5142,7.4632,,0,
                    """);
            writeEntry(output, "routes.txt", """
                    route_id,agency_id,route_short_name,route_long_name,route_type
                    R1,A1,U41,Stadtbahn U41,0
                    R2,A1,440,Bus 440,3
                    """);
            writeEntry(output, "trips.txt", """
                    route_id,service_id,trip_id,trip_headsign,direction_id,block_id,shape_id
                    R1,WKD,T1,Hoerde,0,B1,SH1
                    R2,WKD,T2,Kampstrasse,0,B2,SH3
                    R1,WKD,T3,Hoerde,0,B3,SH1
                    R1,WKD,T4,Dortmund Hbf,1,B4,SH2
                    """);
            writeEntry(output, "stop_times.txt", """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type,shape_dist_traveled
                    T1,24:30:00,24:31:00,S1,1,0,0,0
                    T1,24:32:00,24:33:00,S1B,2,0,0,10
                    T1,25:10:00,25:10:30,S2,3,0,0,20
                    T2,09:00:00,09:00:30,S2,1,0,0,0
                    T3,10:00:00,10:01:00,S1,1,0,0,0
                    T3,10:02:00,10:03:00,S1B,2,0,0,10
                    T3,10:10:00,10:10:30,S2,3,0,0,20
                    T4,11:00:00,11:01:00,S2,1,0,0,0
                    T4,11:10:00,11:10:30,S1,2,0,0,20
                    """);
            writeEntry(output, "shapes.txt", """
                    shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence,shape_dist_traveled
                    SH1,51.4890,7.5000,1,0
                    SH1,51.5000,7.4800,2,10
                    SH1,51.5140,7.4630,3,20
                    SH2,51.5140,7.4630,1,0
                    SH2,51.5000,7.4800,2,10
                    SH2,51.4890,7.5000,3,20
                    SH3,51.5140,7.4630,1,0
                    SH3,51.5141,7.4631,2,5
                    SH3,51.5142,7.4632,3,10
                    """);
            writeEntry(output, "feed_info.txt", """
                    feed_publisher_name,feed_publisher_url,feed_lang,feed_version
                    IXIT Test Feed,https://example.invalid,de,fixture-1
                    """);
            writeEntry(output, "transfers.txt", """
                    from_stop_id,to_stop_id,from_route_id,to_route_id,from_trip_id,to_trip_id,transfer_type,min_transfer_time,service_id
                    S1,S2,,,,,2,180,
                    S1,S1B,R1,R1,T1,T3,4,,WKD
                    S1,S1B,R1,R1,T1,T3,1,,
                    S2,S3,,,,,3,,
                    S_UNKNOWN,S2,,,,,2,300,
                    """);
            writeEntry(output, "calendar.txt", """
                    service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                    WKD,1,1,1,1,1,0,0,20260101,20261231
                    WKD_EXTRA,0,0,0,0,0,0,0,20260101,20261231
                    """);
            if (includeCalendarDates) {
                writeEntry(output, "calendar_dates.txt", """
                        service_id,date,exception_type
                        WKD_EXTRA,20260102,1
                        WKD,20260103,2
                        """);
            }
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
    }

    private static String queryString(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static int queryInt(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static void assertContains(Set<String> values, String expected) {
        assertTrue(values.contains(expected), "Expected token: " + expected + " in " + values);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("Expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) {
            throw new AssertionError(message);
        }
    }

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getSimpleName() + " but caught " + actual, actual);
        }
        throw new AssertionError("Expected " + expected.getSimpleName() + " to be thrown");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
