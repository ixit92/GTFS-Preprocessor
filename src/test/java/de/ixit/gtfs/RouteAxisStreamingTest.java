package de.ixit.gtfs;

import de.ixit.gtfs.model.Route;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RouteAxisStreamingTest {
    @TempDir Path directory;

    private RouteAxisBuilder builder() {
        return new RouteAxisBuilder(List.of(),
                List.of(new Route("R:1", null, "R1", "Route One", 2, null, null)), List.of());
    }

    private void fixture(Path database) throws SQLException {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + database);
             var sql = db.createStatement()) {
            sql.execute("INSERT INTO stop_area_members VALUES ('A','A1','STOP'),('A','A2','STOP'),('B','B1','STOP')");
            sql.execute("""
                    INSERT INTO trips(trip_id,route_id,service_id,direction_id) VALUES
                    ('b','R:1','S',NULL),('a','R:1','S',NULL),('c','R:1','S','0'),
                    ('d','R:1','S',''),('e','R:1','S','1'),('f','R:1','S',NULL),
                    ('g','R:1','S',NULL),('h','UnknownRoute','S',NULL)
                    """);
            sql.execute("""
                    INSERT INTO stop_times(trip_id,stop_id,stop_sequence,arrival_seconds,departure_seconds) VALUES
                    ('b','A1',1,88200,88200),('b','A2',2,88260,88260),('b','B1',3,88320,88320),('b','A1',4,88380,88380),
                    ('a','A1',1,88200,88200),('a','B1',2,88260,88260),('a','A1',3,88320,88320),
                    ('c','A1',1,88200,88200),('c','B1',2,88260,88260),
                    ('d','A1',1,88200,88200),('d','B1',2,88260,88260),
                    ('e','B1',1,88200,88200),('e','A1',2,88260,88260),
                    ('f','MISSING',1,88200,88200),('h','A1',1,88200,88200),('orphan','A1',1,88200,88200)
                    """);
        }
    }

    @Test void preservesGroupingOrderingIdsWarningsAndOverflowTimes() throws Exception {
        Path database = directory.resolve("axes.sqlite");
        try (var writer = SqliteGtfsWriter.create(database)) {
            fixture(database);
            var expected = builder().buildFromDatabase(database);
            assertEquals(5, expected.axes().size());
            assertEquals(10, expected.axisStops().size());
            assertEquals(List.of("a", "d", "c", "e", "h"),
                    expected.axes().stream().map(axis -> axis.representativeTripId()).toList());
            var first = expected.axes().getFirst();
            assertEquals("axis_R_1_none_1_" + Integer.toHexString(Objects.hash("R:1", "none", List.of("A", "B", "A"))), first.axisId());
            assertEquals(2, first.tripCount());
            assertEquals("R1", first.routeShortName());
            assertNull(expected.axes().getLast().routeType());
            assertEquals(1, expected.stats().tripsWithoutStopTimes());
            assertEquals(1, expected.stats().tripsWithoutUsableSequence());
            assertEquals(List.of("f->sequence_1"), expected.stats().unmappedStopSamples());

            List<Long> scanned = new ArrayList<>();
            List<Long> written = new ArrayList<>();
            assertEquals(expected.stats(), writer.writeRouteAxes(builder(), scanned::add, written::add));
            assertEquals(15L, scanned.getLast());
            assertEquals(10L, written.getLast());
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var sql = db.createStatement()) {
                assertEquals(RouteAxisBuilder.RouteAxisStats.from(expected.axes(), expected.axisStops(),
                                Set.of(), 0, 0, 0, Set.of()),
                        SqliteContractValidator.readRouteAxisStats(db, Set.of("route_axes", "route_axis_stops")));
                assertNull(SqliteContractValidator.readRouteAxisStats(db, Set.of("route_axes")));
                try (var rows = sql.executeQuery("SELECT axis_id,sequence_index,area_id FROM route_axis_stops ORDER BY axis_id,sequence_index")) {
                    var actual = new ArrayList<String>();
                    while (rows.next()) actual.add(rows.getString(1) + ":" + rows.getInt(2) + ":" + rows.getString(3));
                    assertEquals(expected.axisStops().stream().map(stop -> stop.axisId() + ":" + stop.sequenceIndex() + ":" + stop.areaId()).sorted().toList(), actual);
                }
                try (var rows = sql.executeQuery("SELECT MIN(departure_seconds) FROM stop_times")) {
                    assertTrue(rows.next());
                    assertEquals(88200, rows.getInt(1));
                }
            }
        }
    }

    @Test void sharesAreaStringsAcrossDifferentSequencesWithoutInterning() throws Exception {
        Path database = directory.resolve("shared.sqlite");
        try (var writer = SqliteGtfsWriter.create(database)) {
            fixture(database);
            List<String> retained = new ArrayList<>();
            builder().streamFromDatabase(database, (axis, sequence) -> {
                for (String area : sequence) if (area.equals("A")) retained.add(area);
                assertThrows(UnsupportedOperationException.class, () -> sequence.add("mutate"));
            }, null);
            assertTrue(retained.size() > 3);
            for (String area : retained) assertSame(retained.getFirst(), area);
            assertNotSame("A", retained.getFirst());
        }
    }

    @Test void streamsAcrossBatchesAndRollsBackOnFailure() throws Exception {
        Path database = directory.resolve("batches.sqlite");
        try (var writer = SqliteGtfsWriter.create(database)) {
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var sql = db.createStatement()) {
                sql.execute("INSERT INTO stop_area_members VALUES ('A','A1','STOP'),('B','B1','STOP')");
                sql.execute("INSERT INTO trips(trip_id,route_id,service_id) VALUES ('long','R:1','S')");
                sql.execute("""
                        WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<25001)
                        INSERT INTO stop_times(trip_id,stop_id,stop_sequence,arrival_seconds,departure_seconds)
                        SELECT 'long',CASE WHEN i%2=0 THEN 'A1' ELSE 'B1' END,i,88200+i,88200+i FROM n
                        """);
            }
            assertThrows(IllegalStateException.class, () -> writer.writeRouteAxes(builder(), null, rows -> {
                if (rows == 20001) throw new IllegalStateException("simulated sink failure after batch flush");
            }));
            try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var sql = db.createStatement();
                 var rows = sql.executeQuery("SELECT (SELECT COUNT(*) FROM route_axes)+(SELECT COUNT(*) FROM route_axis_stops)")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            var stats = writer.writeRouteAxes(builder(), null, null);
            assertEquals(1, stats.axisCount());
            assertEquals(25001, stats.axisStopCount());
        }
    }

    @Test void emptyFeedProducesEmptyStats() throws Exception {
        try (var writer = SqliteGtfsWriter.create(directory.resolve("empty.sqlite"))) {
            var stats = writer.writeRouteAxes(builder(), null, null);
            assertEquals(0, stats.axisCount());
            assertEquals(0, stats.axisStopCount());
        }
    }
}
