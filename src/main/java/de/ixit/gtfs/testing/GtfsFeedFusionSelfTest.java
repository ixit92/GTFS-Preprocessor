package de.ixit.gtfs.testing;

import de.ixit.gtfs.GtfsCsvReader;
import de.ixit.gtfs.GtfsFeedFusion;
import de.ixit.gtfs.GtfsFeedFusionReport;
import de.ixit.gtfs.GtfsPreprocessor;
import de.ixit.gtfs.GtfsRouteTypeFamily;
import de.ixit.gtfs.GtfsStopIdentityResolver;
import de.ixit.gtfs.GtfsTripFusionPlanner;
import de.ixit.gtfs.GtfsZipReader;
import de.ixit.gtfs.PreprocessOptions;
import de.ixit.gtfs.PreprocessReport;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class GtfsFeedFusionSelfTest {
    private GtfsFeedFusionSelfTest() {
    }

    static void run() {
        exactDuplicatesPreferTheHigherPrioritySource();
        containedTripsKeepTheLongerJourney();
        boundarySegmentsRequireJourneyIdentityAndTwoTimedStops();
        ambiguousBranchesRemainSeparate();
        stopIdentityUsesParentsNamesAndCoordinates();
        extendedRouteTypesUseStandardComparisonFamilies();
        fusedZipCanBePreprocessed();
    }

    private static void exactDuplicatesPreferTheHigherPrioritySource() {
        GtfsTripFusionPlanner.Plan plan = new GtfsTripFusionPlanner().plan(List.of(
                trip("DE", 0, "de-trip", "IC4", calls("DE", "A", "B", "C")),
                trip("CH", 1, "ch-trip", "IC4", calls("CH", "A", "B", "C"))
        ));

        assertEquals(1L, count(plan, GtfsTripFusionPlanner.DecisionKind.EXACT_DUPLICATE));
        assertEquals(
                new GtfsTripFusionPlanner.TripKey("DE", "de-trip"),
                plan.canonicalByTrip().get(new GtfsTripFusionPlanner.TripKey("CH", "ch-trip"))
        );
    }

    private static void containedTripsKeepTheLongerJourney() {
        List<GtfsTripFusionPlanner.StopCall> longCalls = calls("DE", "A", "B", "C", "D");
        List<GtfsTripFusionPlanner.StopCall> shortCalls = List.of(
                call("CH", "B", 9 * 3600),
                call("CH", "C", 10 * 3600)
        );
        GtfsTripFusionPlanner.Plan plan = new GtfsTripFusionPlanner().plan(List.of(
                trip("DE", 1, "long", "IC4", longCalls),
                trip("CH", 0, "short", "IC4", shortCalls)
        ));

        assertEquals(1L, count(plan, GtfsTripFusionPlanner.DecisionKind.SUBSET_SUPPRESSED));
        assertEquals(
                new GtfsTripFusionPlanner.TripKey("DE", "long"),
                plan.canonicalByTrip().get(new GtfsTripFusionPlanner.TripKey("CH", "short"))
        );
    }

    private static void boundarySegmentsRequireJourneyIdentityAndTwoTimedStops() {
        List<GtfsTripFusionPlanner.StopCall> left = calls("DE", "A", "B", "C");
        List<GtfsTripFusionPlanner.StopCall> right = List.of(
                call("CH", "B", 9 * 3600),
                call("CH", "C", 10 * 3600),
                call("CH", "D", 11 * 3600)
        );
        GtfsTripFusionPlanner.Plan plan = new GtfsTripFusionPlanner().plan(List.of(
                trip("DE", 0, "west", "IC4", left),
                trip("CH", 1, "east", "IC4", right)
        ));

        assertEquals(1L, count(plan, GtfsTripFusionPlanner.DecisionKind.STITCHED));
        List<GtfsTripFusionPlanner.StopCall> fused = plan.fusedCallsByCanonicalTrip().get(
                new GtfsTripFusionPlanner.TripKey("DE", "west")
        );
        assertEquals(List.of("A", "B", "C", "D"), fused.stream()
                .map(GtfsTripFusionPlanner.StopCall::canonicalStopKey)
                .toList());
    }

    private static void ambiguousBranchesRemainSeparate() {
        List<GtfsTripFusionPlanner.StopCall> left = calls("DE", "A", "B", "C");
        List<GtfsTripFusionPlanner.StopCall> right = List.of(
                call("CH", "A", 8 * 3600),
                call("CH", "B", 9 * 3600),
                call("CH", "D", 10 * 3600)
        );
        GtfsTripFusionPlanner.Plan plan = new GtfsTripFusionPlanner().plan(List.of(
                trip("DE", 0, "branch-west", "IC4", left),
                trip("CH", 1, "branch-east", "IC4", right)
        ));

        assertEquals(1L, count(plan, GtfsTripFusionPlanner.DecisionKind.AMBIGUOUS_KEPT));
        assertEquals(2L, plan.canonicalByTrip().entrySet().stream()
                .filter(entry -> entry.getKey().equals(entry.getValue()))
                .count());
    }

    private static void stopIdentityUsesParentsNamesAndCoordinates() {
        List<GtfsStopIdentityResolver.RawStop> stops = List.of(
                new GtfsStopIdentityResolver.RawStop(
                        "DE", 0, "basel", "8500010", "Basel SBB Bahnhof",
                        47.5474, 7.5896, "", 1
                ),
                new GtfsStopIdentityResolver.RawStop(
                        "DE", 0, "basel-1", "", "Basel SBB Gleis 1",
                        47.5475, 7.5897, "basel", 0
                ),
                new GtfsStopIdentityResolver.RawStop(
                        "CH", 1, "parent", "8500010", "Basel SBB",
                        47.5476, 7.5895, "", 1
                ),
                new GtfsStopIdentityResolver.RawStop(
                        "CH", 1, "quay-a", "", "Basel SBB Quai A",
                        47.5476, 7.5895, "parent", 0
                )
        );
        Map<GtfsStopIdentityResolver.StopKey, String> identities =
                new GtfsStopIdentityResolver().resolve(stops);

        assertEquals(
                identities.get(new GtfsStopIdentityResolver.StopKey("DE", "basel-1")),
                identities.get(new GtfsStopIdentityResolver.StopKey("CH", "quay-a"))
        );
    }

    private static void extendedRouteTypesUseStandardComparisonFamilies() {
        assertEquals(2, GtfsRouteTypeFamily.canonical(2));
        assertEquals(2, GtfsRouteTypeFamily.canonical(102));
        assertEquals(2, GtfsRouteTypeFamily.canonical(109));
        assertEquals(2, GtfsRouteTypeFamily.canonical(300));
        assertEquals(1, GtfsRouteTypeFamily.canonical(400));
        assertEquals(1, GtfsRouteTypeFamily.canonical(500));
        assertEquals(3, GtfsRouteTypeFamily.canonical(700));
        assertEquals(0, GtfsRouteTypeFamily.canonical(900));
        assertEquals(4, GtfsRouteTypeFamily.canonical(1000));
        assertEquals(1100, GtfsRouteTypeFamily.canonical(1100));
        assertEquals(4, GtfsRouteTypeFamily.canonical(1200));
        assertEquals(-1, GtfsRouteTypeFamily.canonical(null));
    }

    private static void fusedZipCanBePreprocessed() {
        try {
            Path directory = Files.createTempDirectory("ixit-gtfs-fusion-");
            Path deFeed = directory.resolve("de.zip");
            Path chFeed = directory.resolve("ch.zip");
            Path fusedFeed = directory.resolve("fused.zip");
            Path fusionReport = directory.resolve("fusion-report.json");
            writeFeed(deFeed, "de-trip", "Basel SBB Bahnhof", "Zuerich HB Bahnhof", 0.0, 2);
            writeFeed(chFeed, "ch-trip", "Basel SBB", "Zuerich HB", 0.0002, 102);
            List<GtfsFeedFusion.Source> sources = List.of(
                    new GtfsFeedFusion.Source("DE", deFeed),
                    new GtfsFeedFusion.Source("CH", chFeed)
            );

            assertThrows(IllegalArgumentException.class, () ->
                    new GtfsFeedFusion().run(sources, deFeed, fusionReport));
            assertTrue(Files.size(deFeed) > 0, "Input feed must remain untouched");

            GtfsFeedFusionReport report = new GtfsFeedFusion().run(
                    sources,
                    fusedFeed,
                    fusionReport
            );

            assertEquals(1L, report.exactDuplicates());
            assertEquals(1L, report.outputTrips());
            assertTrue(Files.isRegularFile(fusionReport), "Expected fusion report");
            List<String> outputAgencyIds = new ArrayList<>();
            List<String> outputTripIds = new ArrayList<>();
            List<String> outputStopTimeTrips = new ArrayList<>();
            List<String> translatedRecordIds = new ArrayList<>();
            List<String> outputStopIds = new ArrayList<>();
            Map<String, String> parentByStop = new LinkedHashMap<>();
            try (GtfsZipReader zip = GtfsZipReader.open(fusedFeed)) {
                GtfsCsvReader.read(zip.openRequired("agency.txt"), row ->
                        outputAgencyIds.add(row.required("agency_id")));
                GtfsCsvReader.read(zip.openRequired("stops.txt"), row -> {
                    String stopId = row.required("stop_id");
                    outputStopIds.add(stopId);
                    parentByStop.put(stopId, row.optional("parent_station"));
                });
                GtfsCsvReader.read(zip.openRequired("trips.txt"), row ->
                        outputTripIds.add(row.required("trip_id")));
                GtfsCsvReader.read(zip.openRequired("stop_times.txt"), row ->
                        outputStopTimeTrips.add(row.required("trip_id")));
                GtfsCsvReader.read(zip.openRequired("translations.txt"), row ->
                        translatedRecordIds.add(row.required("record_id")));
                assertTrue(zip.exists("ixit_fusion_sources.txt"), "Expected source provenance");
                assertTrue(zip.exists("ixit_fusion_stop_mappings.txt"), "Expected stop provenance");
                assertTrue(zip.exists("ixit_fusion_trip_mappings.txt"), "Expected trip provenance");
            }
            assertEquals(List.of("DE::__DEFAULT_AGENCY", "CH::__DEFAULT_AGENCY"), outputAgencyIds);
            assertEquals(
                    List.of(
                            "DE::A", "DE::A1", "DE::B", "DE::B1", "DE::C", "DE::C1",
                            "CH::A1", "CH::B1", "CH::C1"
                    ),
                    outputStopIds
            );
            assertEquals("DE::A", parentByStop.get("DE::A1"));
            assertEquals("DE::A", parentByStop.get("CH::A1"));
            assertEquals("DE::B", parentByStop.get("CH::B1"));
            assertEquals("DE::C", parentByStop.get("CH::C1"));
            assertEquals(List.of("DE::de-trip"), outputTripIds);
            assertEquals(List.of("DE::de-trip", "DE::de-trip", "DE::de-trip"), outputStopTimeTrips);
            assertEquals(
                    List.of("DE::A", "DE::de-trip"),
                    translatedRecordIds
            );

            Path sqlite = directory.resolve("runtime.sqlite");
            PreprocessReport preprocessReport = new GtfsPreprocessor().run(
                    fusedFeed,
                    sqlite,
                    null,
                    PreprocessOptions.stressCoreOnly()
            );
            assertEquals(9L, preprocessReport.stops());
            assertEquals(3L, preprocessReport.stopAreas());
            assertEquals(1L, preprocessReport.trips());
            assertEquals(3L, preprocessReport.stopTimes());
        } catch (Exception ex) {
            throw new AssertionError("Fused GTFS integration failed", ex);
        }
    }

    private static GtfsTripFusionPlanner.TripPattern trip(
            String source,
            int priority,
            String tripId,
            String journeyKey,
            List<GtfsTripFusionPlanner.StopCall> calls
    ) {
        return new GtfsTripFusionPlanner.TripPattern(
                new GtfsTripFusionPlanner.TripKey(source, tripId),
                priority,
                2,
                "ic4",
                journeyKey.toLowerCase(),
                "service-2026",
                calls
        );
    }

    private static List<GtfsTripFusionPlanner.StopCall> calls(String source, String... stops) {
        List<GtfsTripFusionPlanner.StopCall> calls = new ArrayList<>();
        for (int index = 0; index < stops.length; index++) {
            calls.add(call(source, stops[index], (8 + index) * 3600));
        }
        return List.copyOf(calls);
    }

    private static GtfsTripFusionPlanner.StopCall call(String source, String stop, int seconds) {
        return new GtfsTripFusionPlanner.StopCall(source, source + "-" + stop, stop, seconds, seconds);
    }

    private static long count(
            GtfsTripFusionPlanner.Plan plan,
            GtfsTripFusionPlanner.DecisionKind kind
    ) {
        return plan.decisions().stream().filter(decision -> decision.kind() == kind).count();
    }

    private static void writeFeed(
            Path output,
            String tripId,
            String firstName,
            String lastName,
            double coordinateOffset,
            int routeType
    ) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output))) {
            writeEntry(zip, "agency.txt", """
                    agency_id,agency_name,agency_url,agency_timezone
                    ,Fixture,https://example.invalid,Europe/Zurich
                    """);
            writeEntry(zip, "stops.txt", String.format(Locale.ROOT, """
                    stop_id,stop_code,stop_name,stop_lat,stop_lon,location_type,parent_station
                    A,8500010,%s,%f,%f,1,
                    A1,,%s Gleis 1,%f,%f,0,A
                    B,8014482,Basel Bad Bf,%f,%f,1,
                    B1,,Basel Bad Bf Gleis 1,%f,%f,0,B
                    C,8503000,%s,%f,%f,1,
                    C1,,%s Gleis 1,%f,%f,0,C
                    """,
                    firstName, 47.5474 + coordinateOffset, 7.5896 + coordinateOffset,
                    firstName, 47.5474 + coordinateOffset, 7.5896 + coordinateOffset,
                    47.5670 + coordinateOffset, 7.6070 + coordinateOffset,
                    47.5670 + coordinateOffset, 7.6070 + coordinateOffset,
                    lastName, 47.3782 + coordinateOffset, 8.5402 + coordinateOffset,
                    lastName, 47.3782 + coordinateOffset, 8.5402 + coordinateOffset
            ));
            writeEntry(zip, "routes.txt", """
                    route_id,agency_id,route_short_name,route_long_name,route_type
                    R,,IC4,Basel-Zuerich,%d
                    """.formatted(routeType));
            writeEntry(zip, "trips.txt", """
                    route_id,service_id,trip_id,trip_headsign,trip_short_name,direction_id
                    R,S,%s,Zuerich,IC 4,0
                    """.formatted(tripId));
            writeEntry(zip, "stop_times.txt", """
                    trip_id,arrival_time,departure_time,stop_id,stop_sequence
                    %s,08:00:00,08:00:00,A1,1
                    %s,08:30:00,08:30:00,B1,2
                    %s,09:00:00,09:00:00,C1,3
                    """.formatted(tripId, tripId, tripId));
            writeEntry(zip, "calendar.txt", """
                    service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                    S,1,1,1,1,1,0,0,20260101,20261231
                    """);
            writeEntry(zip, "translations.txt", """
                    table_name,field_name,language,translation,record_id
                    stops,stop_name,de,Basel SBB,A
                    trips,trip_headsign,de,Zuerich,%s
                    """.formatted(tripId));
        }
    }

    private static void writeEntry(ZipOutputStream output, String name, String value) throws IOException {
        output.putNextEntry(new ZipEntry(name));
        output.write(value.getBytes(StandardCharsets.UTF_8));
        output.closeEntry();
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

    private static void assertThrows(Class<? extends Throwable> expected, ThrowingOperation operation) {
        try {
            operation.run();
        } catch (Throwable actual) {
            if (expected.isInstance(actual)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getName() + " but caught " + actual, actual);
        }
        throw new AssertionError("Expected " + expected.getName() + " to be thrown");
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }
}
