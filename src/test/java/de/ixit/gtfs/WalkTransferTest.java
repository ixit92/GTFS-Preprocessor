package de.ixit.gtfs;

import de.ixit.gtfs.model.Pathway;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopFootpath;
import de.ixit.gtfs.model.TransferRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WalkTransferTest {
    @TempDir Path directory;

    @Test void walkingAndGtfsMinimumDoNotDoubleCountTheBuffer() {
        assertEquals(225, WalkTimeModel.estimatedWalkSeconds(200));
        assertEquals(285, WalkTimeModel.minimumTransferSeconds(225, 180));
        assertEquals(300, WalkTimeModel.minimumTransferSeconds(225, 300));
        assertEquals(120, WalkTimeModel.minimumTransferSeconds(0, 0));
        assertEquals(Integer.MAX_VALUE, WalkTimeModel.minimumTransferSeconds(Integer.MAX_VALUE, 60));
    }

    @Test void multiSegmentWalkAddsOneBufferAndKeepsDirection() {
        var rows = build(stops(), List.of(path("stairs", "A", "N", 2, 0, 80.0, 100, 50),
                path("hall", "N", "B", 1, 1, 100.0, 80, null)), List.of());
        var forward = find(rows, "A", "B");
        assertTrue(forward.traversable());
        assertEquals(180, forward.walkSeconds());
        assertEquals(240, forward.minTransferSeconds());
        assertEquals(60, forward.transferBufferSeconds());
        assertEquals(List.of("stairs", "hall"), forward.pathwayIds());
        assertEquals(3, forward.pathwayModes());
        assertEquals(180, forward.distanceMeters());
        assertEquals("FEED_PROVIDED", forward.quality());
        assertFalse(find(rows, "B", "A").traversable());
        assertNull(find(rows, "B", "A").walkSeconds());
    }

    @Test void fastestPathIsChosenWithItsOwnDistanceAndProvenance() {
        var paths = List.of(path("long-fast", "A", "B", 1, 1, 900.0, 600, null),
                path("short-slow", "A", "B", 2, 1, 100.0, 700, null));
        var result = find(build(stops(), paths, List.of()), "A", "B");
        assertEquals(600, result.walkSeconds());
        assertEquals(900, result.distanceMeters());
        assertTrue(result.traversable(), "The 400m heuristic limit must not reject a feed-described path");
        assertEquals(List.of("long-fast"), result.pathwayIds());
        var reordered = find(build(stops(), paths.reversed(), List.of()), "A", "B");
        assertEquals(result, reordered);
    }

    @Test void unknownLengthIsNotReplacedByAnInventedWalkDistance() {
        var result = find(build(stops(), List.of(path("lift", "A", "B", 5, 1, null, 140, null)), List.of()), "A", "B");
        assertTrue(result.traversable());
        assertNull(result.distanceMeters());
        assertEquals("GTFS_PATHWAY_TIME_ONLY", result.distanceModel());
        assertEquals(200, result.minTransferSeconds());
    }

    @Test void invalidOrIncompleteGraphNeverCreatesAGeometricShortcut() {
        for (Pathway invalid : List.of(
                path("lift-no-time", "A", "B", 5, 1, 1.0, null, null),
                path("negative", "A", "B", 1, 1, -2.0, 10, null),
                path("zero-time", "A", "B", 1, 1, 10.0, 0, null),
                path("two-way-exit", "A", "B", 7, 1, 10.0, 10, null),
                path("missing-node", "A", "MISSING", 1, 1, 10.0, 10, null))) {
            StopFootpathBuilder builder = new StopFootpathBuilder(stops(), List.of(invalid), List.of());
            assertEquals(1, builder.unusablePathwayRows());
            List<StopFootpath> rows = new ArrayList<>();
            builder.writeTo(rows::add);
            assertFalse(find(rows, "A", "B").traversable(), invalid.pathwayId());
        }
        var incomplete = build(stops(), List.of(path("entry", "A", "N", 1, 1, 10.0, 10, null)), List.of());
        assertFalse(find(incomplete, "A", "B").traversable());
    }

    @Test void stairsAndGatesHaveExplicitEstimates() {
        var stairs = find(build(stops(), List.of(path("steps", "A", "B", 2, 1, null, null, -30)), List.of()), "A", "B");
        assertEquals(40, stairs.walkSeconds());
        assertEquals("ESTIMATED", stairs.quality());
        var gate = find(build(stops(), List.of(path("gate", "A", "B", 6, 1, 12.0, null, null)), List.of()), "A", "B");
        assertEquals(20, gate.walkSeconds());
        assertEquals(1 << 5, gate.pathwayModes());
    }

    @Test void parentRulesApplyWithoutDiscardingExactStopIds() {
        var rules = List.of(rule("P", "P", 2, 420, "STOP"));
        var walk = find(build(stops(), List.of(), rules), "A", "B");
        assertEquals(420, walk.minTransferSeconds());
        assertEquals(420, walk.gtfsMinTransferSeconds());
        var blocked = build(stops(), List.of(), List.of(rule("A", "B", 3, null, "STOP")));
        assertFalse(find(blocked, "A", "B").traversable());
        assertTrue(find(blocked, "B", "A").traversable());
        var stationBlocked = build(stops(), List.of(), List.of(rule("P", "P", 3, null, "STOP")));
        assertFalse(find(stationBlocked, "A", "B").traversable());
    }

    @Test void scopedAndInSeatRulesDoNotBecomeGenericWalkRules() {
        for (TransferRule rule : List.of(rule("A", "B", 3, null, "TRIP"),
                rule("A", "B", 2, 1000, "ROUTE"), rule("A", "B", 4, null, "STOP"),
                rule("A", "B", 5, null, "STOP"))) {
            var walk = find(build(stops(), List.of(), List.of(rule)), "A", "B");
            assertTrue(walk.traversable());
            assertNull(walk.gtfsMinTransferSeconds());
        }
        assertFalse(find(build(stops(), List.of(), List.of(rule("A", "B", 2, null, "STOP"))), "A", "B").traversable());
    }

    @Test void missingCoordinatesRemainUnknownUnlessAPathIsProvided() {
        var missing = List.of(new Stop("A", null, "A", null, null, "P", 0, null),
                new Stop("B", null, "B", null, null, "P", 0, null));
        assertNull(find(build(missing, List.of(), List.of()), "A", "B").walkSeconds());
        var provided = find(build(missing, List.of(path("walk", "A", "B", 1, 1, 90.0, 90, null)), List.of()), "A", "B");
        assertTrue(provided.traversable());
        assertEquals(150, provided.minTransferSeconds());
    }

    @Test void childBoardingAreasCanSupplyAPlatformWalkWithoutTeleportation() {
        var stops = new ArrayList<>(stops());
        stops.add(new Stop("A1", null, "A1", null, null, "A", 4, null));
        stops.add(new Stop("B1", null, "B1", null, null, "B", 4, null));
        var result = find(build(stops, List.of(path("board", "A1", "B1", 1, 0, 150.0, 150, null)), List.of()), "A", "B");
        assertTrue(result.traversable());
        assertEquals(List.of("board"), result.pathwayIds());
    }

    @Test void genericTransferEdgeCannotBypassWalkingTimeOrDirection() {
        var rules = List.of(rule("A", "B", 2, 120, "STOP"), rule("B", "A", 0, null, "STOP"));
        var builder = new TransferEdgeBuilder(stops(), StopAreaBuilder.fromStops(stops()),
                List.of(path("walk", "A", "B", 1, 0, 300.0, 300, null)));
        var rows = builder.build(rules).edges().stream().filter(e -> "GTFS_TRANSFERS".equals(e.source())).toList();
        assertEquals(360, rows.get(0).fromStopId().equals("A") ? rows.get(0).minTransferSeconds() : rows.get(1).minTransferSeconds());
        assertTrue(rows.stream().anyMatch(e -> e.fromStopId().equals("B") && !e.traversable()));
    }

    @Test void quotedPathwayIdsRoundTripThroughTheCsvParser() throws Exception {
        var parsed = GtfsParsers.readPathways(new ByteArrayInputStream(("pathway_id,from_stop_id,to_stop_id,pathway_mode,is_bidirectional,length,traversal_time,stair_count\n"
                + "\"walk, \"\"north\"\"\",A,B,1,0,150.5,160,\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals("walk, \"north\"", parsed.getFirst().pathwayId());
        assertEquals(150.5, parsed.getFirst().lengthMeters());
    }

    @Test void zipToSqlitePreservesWalksAndRejectsTamperedTiming() throws Exception {
        Path input = directory.resolve("station.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(input))) {
            entry(zip, "stops.txt", "stop_id,stop_name,stop_lat,stop_lon,parent_station,location_type\nP,Test Hbf,51,7,,1\nA,Platform A,51,7,P,0\nB,Platform B,51.0003,7,P,0\nN,Hall,51.0001,7,P,3\n");
            entry(zip, "agency.txt", "agency_id,agency_name,agency_url,agency_timezone\nAG,Test,https://example.org,Europe/Berlin\n");
            entry(zip, "routes.txt", "route_id,agency_id,route_short_name,route_type\nR,AG,R,2\n");
            entry(zip, "trips.txt", "trip_id,route_id,service_id\nT,R,S\n");
            entry(zip, "stop_times.txt", "trip_id,stop_id,stop_sequence,arrival_time,departure_time\nT,A,1,24:30:00,24:30:00\nT,B,2,25:10:00,25:10:00\n");
            entry(zip, "calendar_dates.txt", "service_id,date,exception_type\nS,20260905,1\n");
            entry(zip, "transfers.txt", "from_stop_id,to_stop_id,transfer_type,min_transfer_time\nA,B,2,300\n");
            entry(zip, "pathways.txt", "pathway_id,from_stop_id,to_stop_id,pathway_mode,is_bidirectional,length,traversal_time\nfirst,A,N,1,0,120,100\nsecond,N,B,1,0,96,80\n");
        }
        Path database = directory.resolve("ixit_gtfs.sqlite");
        var report = new GtfsPreprocessor().run(input, database);
        assertTrue(report.transferFootpathAudit().pass());
        assertEquals(2, report.transferFootpathAudit().rawPathways());
        assertEquals(1, report.transferFootpathAudit().pathwayFootpaths());
        assertTrue(report.toConsoleText().contains("pathway_footpaths: 1"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            List<String> queryPlan = new ArrayList<>();
            try (var rows = statement.executeQuery("EXPLAIN QUERY PLAN " + TransferFootpathAuditor.PROHIBITED_WALKS_QUERY)) {
                while (rows.next()) queryPlan.add(rows.getString("detail"));
            }
            assertTrue(queryPlan.stream().anyMatch(detail -> detail.contains("SEARCH raw USING INDEX idx_transfers_from_to")),
                    "Large feeds must not scan the NULL trip-scope bucket for every footpath: " + queryPlan);
            try (var rows = statement.executeQuery("SELECT walk_seconds, transfer_buffer_seconds, gtfs_min_transfer_seconds, min_transfer_seconds, pathway_ids FROM stop_footpaths WHERE from_stop_id='A' AND to_stop_id='B'")) {
                assertTrue(rows.next());
                assertEquals(180, rows.getInt(1));
                assertEquals(60, rows.getInt(2));
                assertEquals(300, rows.getInt(3));
                assertEquals(300, rows.getInt(4));
                assertEquals("[\"first\",\"second\"]", rows.getString(5));
            }
            try (var rows = statement.executeQuery("SELECT MAX(departure_seconds) FROM stop_times")) {
                assertTrue(rows.next());
                assertEquals(90600, rows.getInt(1));
            }
            statement.executeUpdate("UPDATE stop_footpaths SET min_transfer_seconds=180 WHERE from_stop_id='A' AND to_stop_id='B'");
        }
        assertFalse(TransferFootpathAuditor.audit(database).pass());
        assertTrue(assertThrows(IllegalStateException.class, () -> SqliteContractValidator.validate(database))
                .getMessage().contains("invalid_walk_components=1"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE stop_footpaths SET min_transfer_seconds=300, pathway_ids='[\"second\",\"first\"]' WHERE from_stop_id='A' AND to_stop_id='B'");
        }
        assertFalse(TransferFootpathAuditor.audit(database).pass(), "Known IDs in reversed order must fail provenance replay");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE stop_footpaths SET pathway_ids='[\"first\",\"second\"]' WHERE from_stop_id='A' AND to_stop_id='B'");
            statement.executeUpdate("UPDATE transfers SET transfer_type=3, min_transfer_time=NULL");
        }
        assertTrue(TransferFootpathAuditor.audit(database).prohibitedWalks() > 0);
    }

    @Test void coincidentPlatformsStillRequireAChangeBuffer() {
        var sameCoordinates = List.of(new Stop("A", null, "A", 51.0, 7.0, "P", 0, "1"),
                new Stop("B", null, "B", 51.0, 7.0, "P", 0, "2"));
        var walk = find(build(sameCoordinates, List.of(), List.of()), "A", "B");
        assertEquals(0, walk.walkSeconds());
        assertEquals(120, walk.minTransferSeconds());
        assertEquals("SAME_STOP_AREA_GEOMETRY", walk.source());
    }

    private static List<Stop> stops() {
        return List.of(new Stop("P", null, "Test Hbf", 51.0, 7.0, null, 1, null),
                new Stop("A", null, "Platform A", 51.0, 7.0, "P", 0, "1"),
                new Stop("B", null, "Platform B", 51.0003, 7.0, "P", 0, "2"),
                new Stop("N", null, "Hall", 51.0001, 7.0, "P", 3, null));
    }

    private static List<StopFootpath> build(List<Stop> stops, List<Pathway> paths, List<TransferRule> rules) {
        List<StopFootpath> result = new ArrayList<>();
        new StopFootpathBuilder(stops, paths, rules).writeTo(result::add);
        return result;
    }

    private static StopFootpath find(List<StopFootpath> paths, String from, String to) {
        return paths.stream().filter(p -> p.fromStopId().equals(from) && p.toStopId().equals(to)).findFirst().orElseThrow();
    }

    private static Pathway path(String id, String from, String to, int mode, int bidirectional, Double length, Integer time, Integer stairs) {
        return new Pathway(id, from, to, mode, bidirectional, length, time, stairs);
    }

    private static TransferRule rule(String from, String to, int type, Integer time, String scope) {
        String semantic = switch (type) {
            case 2 -> "MINIMUM_TIME";
            case 3 -> "PROHIBITED";
            case 4 -> "IN_SEAT_ALLOWED";
            case 5 -> "IN_SEAT_FORBIDDEN";
            default -> "RECOMMENDED";
        };
        return new TransferRule("rule_" + from + to, (long) from.hashCode(), "P", "P", from, to,
                type, time, semantic, scope, type < 3 && "STOP".equals(scope), "GTFS_TRANSFERS", "HIGH", "test");
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
