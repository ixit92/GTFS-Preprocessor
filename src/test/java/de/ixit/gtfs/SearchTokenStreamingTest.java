package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopSearchToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SearchTokenStreamingTest {
    @TempDir Path directory;

    @Test void preservesTokensOrderDuplicatesEmptyWarningsAndSourceIds() throws Exception {
        List<Stop> stops = new ArrayList<>();
        String[] names = {"Dortmund Hbf Hbf", "DO-H\u00f6rde Bf", "Kampstra\u00dfe U",
                "S-Bahn U-Bahn", "  M\u00fcnchen & Stra\u00dfe + S  ", "", "---", null,
                " ", "!!!", "...", "Dortmund Hbf Hbf"};
        for (int i = 0; i < names.length; i++) {
            stops.add(new Stop("s" + i, null, names[i], null, null, i == 1 ? "s0" : null, 0, null));
        }
        var areas = StopAreaBuilder.fromStops(stops);
        var expected = StopSearchTokenBuilder.build(stops, areas);
        Path database = directory.resolve("tokens.sqlite");
        try (var writer = SqliteGtfsWriter.create(database)) {
            writer.writeStops(stops);
            writer.writeStopAreas(areas);
            List<Long> progress = new ArrayList<>();
            var stats = writer.writeStopSearchTokens(progress::add);
            assertEquals(expected.tokens().size(), stats.tokenCount());
            assertEquals(expected.duplicateTokenCount(), stats.duplicateTokenCount());
            assertTrue(stats.duplicateTokenCount() > 0);
            assertEquals(expected.emptyTokenSources().size(), stats.emptyTokenSourceCount());
            assertEquals(expected.emptyTokenSources().stream().limit(5).toList(), stats.emptyTokenSamples());
            assertEquals(stops.size() + areas.size(), progress.getLast());
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + database);
                 var sql = db.createStatement();
                 var rows = sql.executeQuery("SELECT stop_id,area_id,token,token_type,source FROM stop_search_tokens ORDER BY rowid")) {
                List<StopSearchToken> actual = new ArrayList<>();
                while (rows.next()) actual.add(new StopSearchToken(rows.getString(1), rows.getString(2),
                        rows.getString(3), rows.getString(4), rows.getString(5)));
                assertEquals(expected.tokens(), actual);
            }
        }
    }

    @Test void rollsBackFlushedBatchesOnSqlAndProgressFailuresAndCanRetry() throws Exception {
        Path database = directory.resolve("batches.sqlite");
        try (var writer = SqliteGtfsWriter.create(database);
             var db = DriverManager.getConnection("jdbc:sqlite:" + database);
             var sql = db.createStatement()) {
            sql.execute("""
                    WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<12001)
                    INSERT INTO stops(stop_id,stop_name) SELECT 's'||i,'Hbf' FROM n
                    """);
            assertThrows(IllegalStateException.class, () -> writer.writeStopSearchTokens(rows -> {
                if (rows == 12000) throw new IllegalStateException("failure after batch flush");
            }));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM stop_search_tokens")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            sql.execute("""
                    CREATE TRIGGER fail_tokens BEFORE INSERT ON stop_search_tokens
                    WHEN NEW.stop_id='s12001' BEGIN SELECT RAISE(ABORT,'simulated SQL failure'); END
                    """);
            assertThrows(SQLException.class, () -> writer.writeStopSearchTokens(rows -> {}));
            try (var rows = sql.executeQuery("SELECT COUNT(*) FROM stop_search_tokens")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getInt(1));
            }
            sql.execute("DROP TRIGGER fail_tokens");
            assertEquals(36003, writer.writeStopSearchTokens(rows -> {}).tokenCount());
        }
    }

    @Test void emptyFeedReturnsEmptyStatistics() throws Exception {
        try (var writer = SqliteGtfsWriter.create(directory.resolve("empty.sqlite"))) {
            assertEquals(new StopSearchTokenBuilder.StreamingStats(0, 0, 0, List.of()),
                    writer.writeStopSearchTokens(rows -> {}));
        }
    }
}
