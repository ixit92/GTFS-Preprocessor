package de.ixit.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.HexFormat;

public final class SourceStaticProvenanceBuilder {
    private static final int BATCH_SIZE = 10_000;

    public BuildResult build(Path sourceStaticDatabase, Path sourceZip, String sourceId, Path outputDatabase) throws Exception {
        Path source = requiredFile(sourceStaticDatabase, "Source static database");
        Path zip = requiredFile(sourceZip, "Source GTFS ZIP");
        Path output = outputDatabase.toAbsolutePath().normalize();
        if (!sourceId.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("sourceId must contain uppercase letters, digits and underscores");
        if (Files.exists(output)) throw new IllegalArgumentException("Source provenance database is immutable: " + output);
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Class.forName("org.sqlite.JDBC");
        long started = System.nanoTime();
        BuildResult result;
        try (Connection input = DriverManager.getConnection(readOnlyUrl(source)); Connection target = DriverManager.getConnection("jdbc:sqlite:" + temporary)) {
            createSchema(target);
            target.setAutoCommit(false);
            putFeed(target, sourceId, zip);
            long stops = copyStops(input, target, sourceId);
            long trips = copyTrips(input, target, sourceId);
            long stopTimes = copyStopTimes(input, target, sourceId);
            try (PreparedStatement statement = target.prepareStatement("UPDATE source_static_feeds SET source_stop_count=?, source_trip_count=?, source_stop_time_count=? WHERE source_id=?")) {
                statement.setLong(1, stops); statement.setLong(2, trips); statement.setLong(3, stopTimes); statement.setString(4, sourceId); statement.executeUpdate();
            }
            target.commit();
            createIndexes(target);
            target.commit();
            result = new BuildResult(stops, trips, stopTimes, (System.nanoTime() - started) / 1_000_000L, Files.size(temporary));
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        moveAtomically(temporary, output);
        return new BuildResult(result.stops(), result.trips(), result.stopTimes(), result.elapsedMs(), Files.size(output));
    }

    private static void createSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("CREATE TABLE source_static_feeds(source_id TEXT PRIMARY KEY, source_zip_sha256 TEXT NOT NULL, generated_at TEXT NOT NULL, source_stop_count INTEGER NOT NULL, source_trip_count INTEGER NOT NULL, source_stop_time_count INTEGER NOT NULL)");
            statement.execute("CREATE TABLE source_static_stops(source_id TEXT NOT NULL, source_stop_id TEXT NOT NULL, source_stop_code TEXT, source_global_stop_id TEXT, source_stop_name TEXT, parent_source_stop_id TEXT, platform_code TEXT, PRIMARY KEY(source_id, source_stop_id)) WITHOUT ROWID");
            statement.execute("CREATE TABLE source_static_trips(source_id TEXT NOT NULL, source_trip_id TEXT NOT NULL, source_route_id TEXT NOT NULL, source_service_id TEXT NOT NULL, source_direction_id TEXT, source_headsign TEXT, PRIMARY KEY(source_id, source_trip_id)) WITHOUT ROWID");
            statement.execute("CREATE TABLE source_static_stop_times(source_id TEXT NOT NULL, source_trip_id TEXT NOT NULL, source_stop_sequence INTEGER NOT NULL, source_stop_id TEXT NOT NULL, arrival_seconds INTEGER NOT NULL, departure_seconds INTEGER NOT NULL, PRIMARY KEY(source_id, source_trip_id, source_stop_sequence)) WITHOUT ROWID");
        }
    }

    private static void putFeed(Connection target, String sourceId, Path zip) throws Exception {
        try (PreparedStatement statement = target.prepareStatement("INSERT INTO source_static_feeds VALUES (?, ?, ?, 0, 0, 0)")) {
            statement.setString(1, sourceId); statement.setString(2, sha256(zip)); statement.setString(3, Instant.now().toString()); statement.executeUpdate();
        }
    }

    private static long copyStops(Connection input, Connection target, String sourceId) throws Exception {
        String read = "SELECT stop_id, stop_code, stop_name, parent_station, platform_code FROM stops";
        String write = "INSERT INTO source_static_stops VALUES (?, ?, ?, ?, ?, ?, ?)";
        return copy(input, target, read, write, sourceId, 5, (rows, statement, id) -> {
            String stopId = rows.getString(1);
            statement.setString(1, id); statement.setString(2, stopId); statement.setString(3, rows.getString(2));
            statement.setString(4, stopId != null && stopId.startsWith("de:") ? stopId : null);
            statement.setString(5, rows.getString(3)); statement.setString(6, rows.getString(4)); statement.setString(7, rows.getString(5));
        });
    }

    private static long copyTrips(Connection input, Connection target, String sourceId) throws Exception {
        return copy(input, target, "SELECT trip_id, route_id, service_id, direction_id, trip_headsign FROM trips", "INSERT INTO source_static_trips VALUES (?, ?, ?, ?, ?, ?)", sourceId, 5, (rows, statement, id) -> {
            statement.setString(1, id); for (int index = 1; index <= 5; index++) statement.setString(index + 1, rows.getString(index));
        });
    }

    private static long copyStopTimes(Connection input, Connection target, String sourceId) throws Exception {
        return copy(input, target, "SELECT trip_id, stop_sequence, stop_id, arrival_seconds, departure_seconds FROM stop_times", "INSERT INTO source_static_stop_times VALUES (?, ?, ?, ?, ?, ?)", sourceId, 5, (rows, statement, id) -> {
            statement.setString(1, id); statement.setString(2, rows.getString(1)); statement.setInt(3, rows.getInt(2)); statement.setString(4, rows.getString(3)); statement.setInt(5, rows.getInt(4)); statement.setInt(6, rows.getInt(5));
        });
    }

    private static long copy(Connection input, Connection target, String readSql, String writeSql, String sourceId, int columns, RowWriter writer) throws Exception {
        long count = 0;
        try (Statement read = input.createStatement(); ResultSet rows = read.executeQuery(readSql); PreparedStatement write = target.prepareStatement(writeSql)) {
            while (rows.next()) { writer.write(rows, write, sourceId); write.addBatch(); if (++count % BATCH_SIZE == 0) { write.executeBatch(); target.commit(); } }
            write.executeBatch();
        }
        return count;
    }

    private static void createIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_source_static_stops_global ON source_static_stops(source_global_stop_id)");
            statement.execute("CREATE INDEX idx_source_static_stops_code ON source_static_stops(source_stop_code)");
            statement.execute("CREATE INDEX idx_source_static_stop_times_stop ON source_static_stop_times(source_stop_id, departure_seconds)");
        }
    }

    private static Path requiredFile(Path path, String label) { Path resolved = path.toAbsolutePath().normalize(); if (!Files.isRegularFile(resolved)) throw new IllegalArgumentException(label + " missing: " + resolved); return resolved; }
    private static String readOnlyUrl(Path path) { return "jdbc:sqlite:" + path.toUri() + "?mode=ro&immutable=1"; }
    private static String sha256(Path path) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (InputStream input = Files.newInputStream(path)) { byte[] buffer = new byte[1 << 20]; for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read); } return HexFormat.of().formatHex(digest.digest()); }
    private static void moveAtomically(Path source, Path target) throws IOException { try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); } }
    private interface RowWriter { void write(ResultSet rows, PreparedStatement statement, String sourceId) throws Exception; }
    public record BuildResult(long stops, long trips, long stopTimes, long elapsedMs, long bytes) {}
}
