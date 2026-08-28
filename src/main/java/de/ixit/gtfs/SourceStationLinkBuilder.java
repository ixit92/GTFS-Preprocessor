package de.ixit.gtfs;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Produces a conservative bridge from a provider's source-static station identifiers to the IXIT
 * canonical station graph. Ambiguous and name-only matches are intentionally omitted.
 */
public final class SourceStationLinkBuilder {
    private static final int MAX_COORDINATE_DISTANCE_METERS = 35;
    private static final int MAX_UNIQUE_COORDINATE_DISTANCE_METERS = 12;
    private static final double COORDINATE_GRID_DEGREES = 0.0002d;

    public BuildResult build(Path sourceStaticDatabase, Path runtimeDatabase, String sourceId, Path outputDatabase) throws Exception {
        return build(sourceStaticDatabase, runtimeDatabase, sourceId, outputDatabase, null);
    }

    public BuildResult build(
            Path sourceStaticDatabase,
            Path runtimeDatabase,
            String sourceId,
            Path outputDatabase,
            String declaredRuntimeDataVersion) throws Exception {
        Path source = requiredFile(sourceStaticDatabase, "Source static database");
        Path runtime = requiredFile(runtimeDatabase, "IXIT runtime database");
        Path output = outputDatabase.toAbsolutePath().normalize();
        if (!sourceId.matches("[A-Z][A-Z0-9_]{1,63}")) throw new IllegalArgumentException("sourceId must contain uppercase letters, digits and underscores");
        if (Files.exists(output)) throw new IllegalArgumentException("Source station link database is immutable: " + output);
        Files.createDirectories(output.getParent());
        Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
        Files.deleteIfExists(temporary);
        Class.forName("org.sqlite.JDBC");

        long started = System.nanoTime();
        BuildResult result;
        try (Connection sourceConnection = DriverManager.getConnection(readOnlyUrl(source));
             Connection runtimeConnection = DriverManager.getConnection(readOnlyUrl(runtime));
             Connection targetConnection = DriverManager.getConnection("jdbc:sqlite:" + temporary)) {
            requireTables(sourceConnection, "stops");
            requireTables(runtimeConnection, "stops", "stop_area_members", "canonical_stop_area_members", "canonical_stop_areas");
            createSchema(targetConnection);
            targetConnection.setAutoCommit(false);

            Map<String, SourceStop> sourceStops = readSourceStops(sourceConnection);
            Map<String, SourceStation> sourceStations = groupSourceStations(sourceStops);
            RuntimeIndex runtimeIndex = readRuntimeIndex(runtimeConnection);
            String runtimeDataVersion = resolveRuntimeDataVersion(runtimeConnection, declaredRuntimeDataVersion);
            Counts counts = writeLinks(targetConnection, sourceId, sourceStations, runtimeIndex);
            try (PreparedStatement statement = targetConnection.prepareStatement("INSERT INTO source_station_link_metadata VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                statement.setString(1, sourceId);
                statement.setString(2, runtimeDataVersion);
                statement.setString(3, Instant.now().toString());
                statement.setInt(4, sourceStations.size());
                statement.setInt(5, counts.exactGlobalId());
                statement.setInt(6, counts.exactStopCode());
                statement.setInt(7, counts.coordinateChecked());
                statement.setInt(8, counts.coordinateUnique());
                statement.executeUpdate();
            }
            targetConnection.commit();
            createIndexes(targetConnection);
            targetConnection.commit();
            result = new BuildResult(sourceStations.size(), counts.exactGlobalId(), counts.exactStopCode(), counts.coordinateChecked(), counts.coordinateUnique(), counts.ambiguousOrUnmatched(), (System.nanoTime() - started) / 1_000_000L, Files.size(temporary));
        } catch (Exception exception) {
            Files.deleteIfExists(temporary);
            throw exception;
        }
        moveAtomically(temporary, output);
        return new BuildResult(result.sourceStationCount(), result.exactGlobalIdCount(), result.exactStopCodeCount(), result.coordinateNameCheckedCount(), result.coordinateUniqueCount(), result.ambiguousOrUnmatchedCount(), result.elapsedMs(), Files.size(output));
    }

    private static Map<String, SourceStop> readSourceStops(Connection connection) throws Exception {
        Map<String, SourceStop> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT stop_id, stop_code, stop_name, stop_lat, stop_lon, parent_station FROM stops")) {
            while (rows.next()) {
                String id = rows.getString(1);
                if (id == null || id.isBlank()) continue;
                result.put(id, new SourceStop(id, trim(rows.getString(2)), trim(rows.getString(3)), nullableDouble(rows, 4), nullableDouble(rows, 5), trim(rows.getString(6))));
            }
        }
        return result;
    }

    private static Map<String, SourceStation> groupSourceStations(Map<String, SourceStop> stops) {
        Map<String, SourceStation> stations = new LinkedHashMap<>();
        for (SourceStop stop : stops.values()) {
            String rootId = rootId(stop, stops);
            SourceStation station = stations.computeIfAbsent(rootId, ignored -> new SourceStation(rootId));
            station.add(stop);
        }
        return stations;
    }

    private static String rootId(SourceStop stop, Map<String, SourceStop> stops) {
        SourceStop current = stop;
        Set<String> seen = new HashSet<>();
        while (current.parentStation() != null && !current.parentStation().isBlank() && seen.add(current.stopId())) {
            SourceStop parent = stops.get(current.parentStation());
            if (parent == null) break;
            current = parent;
        }
        return current.stopId();
    }

    private static RuntimeIndex readRuntimeIndex(Connection connection) throws Exception {
        RuntimeIndex index = new RuntimeIndex();
        String sql = """
                SELECT stop.stop_id, stop.stop_code, stop.stop_name_normalized, stop.stop_lat, stop.stop_lon,
                       member.canonical_area_id, canonical.primary_stop_area_id
                FROM stops stop
                JOIN stop_area_members area_member ON area_member.stop_id = stop.stop_id
                JOIN canonical_stop_area_members member ON member.area_id = area_member.area_id
                JOIN canonical_stop_areas canonical ON canonical.canonical_area_id = member.canonical_area_id
                """;
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                RuntimeStop runtimeStop = new RuntimeStop(rows.getString(1), trim(rows.getString(2)), trim(rows.getString(3)), nullableDouble(rows, 4), nullableDouble(rows, 5), rows.getString(6), rows.getString(7));
                index.add(runtimeStop);
            }
        }
        return index;
    }

    private static Counts writeLinks(Connection target, String sourceId, Map<String, SourceStation> sourceStations, RuntimeIndex runtimeIndex) throws Exception {
        Counts counts = new Counts();
        String sql = "INSERT INTO source_station_links VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = target.prepareStatement(sql)) {
            for (SourceStation sourceStation : sourceStations.values()) {
                Match match = resolve(sourceStation, runtimeIndex);
                if (match == null) {
                    counts = counts.withAmbiguousOrUnmatched();
                    continue;
                }
                statement.setString(1, sourceId);
                statement.setString(2, sourceStation.stationId());
                statement.setString(3, match.runtimeStop().canonicalAreaId());
                statement.setString(4, match.runtimeStop().primaryAreaId());
                statement.setString(5, match.method().name());
                statement.setString(6, match.sourceStopId());
                statement.setString(7, match.runtimeStop().stopId());
                if (match.distanceMeters() == null) statement.setNull(8, java.sql.Types.INTEGER); else statement.setInt(8, match.distanceMeters());
                statement.setString(9, "EXACT");
                statement.addBatch();
                counts = counts.with(match.method());
            }
            statement.executeBatch();
        }
        return counts;
    }

    private static Match resolve(SourceStation station, RuntimeIndex index) {
        Match exactGlobal = uniqueMatch(station, SourceMatchMethod.EXACT_GLOBAL_ID, index);
        if (exactGlobal != null) return exactGlobal;
        Match exactCode = uniqueMatch(station, SourceMatchMethod.EXACT_STOP_CODE, index);
        if (exactCode != null) return exactCode;
        Match coordinateNameChecked = uniqueMatch(station, SourceMatchMethod.COORDINATE_NAME_CHECKED, index);
        return coordinateNameChecked != null ? coordinateNameChecked : uniqueCoordinateMatch(station, index);
    }

    private static Match uniqueMatch(SourceStation station, SourceMatchMethod method, RuntimeIndex index) {
        Map<String, Match> candidates = new LinkedHashMap<>();
        for (SourceStop sourceStop : station.members()) {
            List<RuntimeStop> matches = switch (method) {
                case EXACT_GLOBAL_ID -> sourceStop.stopId().startsWith("de:") ? index.byStopId().getOrDefault(sourceStop.stopId(), List.of()) : List.of();
                case EXACT_STOP_CODE -> sourceStop.stopCode() == null ? List.of() : index.byStopCode().getOrDefault(sourceStop.stopCode(), List.of());
                case COORDINATE_NAME_CHECKED -> index.byNormalizedName().getOrDefault(normalize(sourceStop.stopName()), List.of());
                case COORDINATE_UNIQUE -> List.of();
            };
            for (RuntimeStop runtimeStop : matches) {
                Integer distance = coordinateDistance(sourceStop, runtimeStop);
                if (method == SourceMatchMethod.COORDINATE_NAME_CHECKED && (distance == null || distance > MAX_COORDINATE_DISTANCE_METERS)) continue;
                candidates.put(runtimeStop.canonicalAreaId(), new Match(method, sourceStop.stopId(), runtimeStop, distance));
            }
        }
        return candidates.size() == 1 ? candidates.values().iterator().next() : null;
    }

    private static Match uniqueCoordinateMatch(SourceStation station, RuntimeIndex index) {
        Map<String, Match> candidates = new LinkedHashMap<>();
        for (SourceStop sourceStop : station.members()) {
            if (sourceStop.latitude() == null || sourceStop.longitude() == null) continue;
            CoordinateCell origin = CoordinateCell.of(sourceStop.latitude(), sourceStop.longitude());
            for (int latOffset = -1; latOffset <= 1; latOffset++) {
                for (int lonOffset = -1; lonOffset <= 1; lonOffset++) {
                    CoordinateCell cell = new CoordinateCell(origin.latitudeCell() + latOffset, origin.longitudeCell() + lonOffset);
                    for (RuntimeStop runtimeStop : index.byCoordinateCell().getOrDefault(cell, List.of())) {
                        Integer distance = coordinateDistance(sourceStop, runtimeStop);
                        if (distance == null || distance > MAX_UNIQUE_COORDINATE_DISTANCE_METERS) continue;
                        candidates.put(runtimeStop.canonicalAreaId(), new Match(SourceMatchMethod.COORDINATE_UNIQUE, sourceStop.stopId(), runtimeStop, distance));
                    }
                }
            }
        }
        return candidates.size() == 1 ? candidates.values().iterator().next() : null;
    }

    private static Integer coordinateDistance(SourceStop source, RuntimeStop runtime) {
        if (source.latitude() == null || source.longitude() == null || runtime.latitude() == null || runtime.longitude() == null) return null;
        double lat1 = Math.toRadians(source.latitude());
        double lat2 = Math.toRadians(runtime.latitude());
        double dLat = Math.toRadians(runtime.latitude() - source.latitude());
        double dLon = Math.toRadians(runtime.longitude() - source.longitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return (int) Math.round(2 * 6_371_000d * Math.asin(Math.sqrt(h)));
    }

    private static void createSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("CREATE TABLE source_station_links(source_id TEXT NOT NULL, source_station_id TEXT NOT NULL, runtime_canonical_area_id TEXT NOT NULL, runtime_primary_area_id TEXT NOT NULL, match_method TEXT NOT NULL, source_match_stop_id TEXT NOT NULL, runtime_match_stop_id TEXT NOT NULL, distance_meters INTEGER, quality TEXT NOT NULL, PRIMARY KEY(source_id, source_station_id)) WITHOUT ROWID");
            statement.execute("CREATE TABLE source_station_link_metadata(source_id TEXT PRIMARY KEY, runtime_data_version TEXT, generated_at TEXT NOT NULL, source_station_count INTEGER NOT NULL, exact_global_id_count INTEGER NOT NULL, exact_stop_code_count INTEGER NOT NULL, coordinate_name_checked_count INTEGER NOT NULL, coordinate_unique_count INTEGER NOT NULL)");
        }
    }

    private static void createIndexes(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX idx_source_station_links_runtime_canonical ON source_station_links(runtime_canonical_area_id)");
            statement.execute("CREATE INDEX idx_source_station_links_runtime_primary ON source_station_links(runtime_primary_area_id)");
            statement.execute("CREATE INDEX idx_source_station_links_method ON source_station_links(match_method)");
        }
    }

    private static void requireTables(Connection connection, String... tables) throws Exception {
        for (String table : tables) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
                statement.setString(1, table);
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next()) throw new IllegalArgumentException("Required table missing: " + table);
                }
            }
        }
    }

    private static String metadata(Connection connection, String key) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("SELECT value FROM ixit_metadata WHERE key=?")) {
            statement.setString(1, key);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getString(1) : ""; }
        } catch (java.sql.SQLException ignored) {
            return "";
        }
    }

    private static String resolveRuntimeDataVersion(Connection connection, String declaredRuntimeDataVersion) throws Exception {
        String embeddedVersion = trim(metadata(connection, "data_version"));
        String declaredVersion = trim(declaredRuntimeDataVersion);
        if (declaredVersion != null && embeddedVersion != null && !declaredVersion.equals(embeddedVersion)) {
            throw new IllegalArgumentException("Declared runtime data version does not match SQLite metadata");
        }
        String resolved = declaredVersion != null ? declaredVersion : embeddedVersion;
        if (resolved == null) {
            throw new IllegalArgumentException("Runtime data version is required via SQLite metadata or --runtime-data-version");
        }
        return resolved;
    }

    private static String normalize(String value) {
        return value == null ? "" : StopNameNormalizer.normalize(value);
    }

    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static Double nullableDouble(ResultSet rows, int column) throws Exception { Object value = rows.getObject(column); return value == null ? null : ((Number) value).doubleValue(); }
    private static Path requiredFile(Path path, String label) { Path resolved = path.toAbsolutePath().normalize(); if (!Files.isRegularFile(resolved)) throw new IllegalArgumentException(label + " missing: " + resolved); return resolved; }
    private static String readOnlyUrl(Path path) { return "jdbc:sqlite:" + path.toUri() + "?mode=ro&immutable=1"; }
    private static void moveAtomically(Path source, Path target) throws IOException { try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); } }

    private enum SourceMatchMethod { EXACT_GLOBAL_ID, EXACT_STOP_CODE, COORDINATE_NAME_CHECKED, COORDINATE_UNIQUE }
    private record SourceStop(String stopId, String stopCode, String stopName, Double latitude, Double longitude, String parentStation) {}
    private record RuntimeStop(String stopId, String stopCode, String normalizedName, Double latitude, Double longitude, String canonicalAreaId, String primaryAreaId) {}
    private record Match(SourceMatchMethod method, String sourceStopId, RuntimeStop runtimeStop, Integer distanceMeters) {}
    private record Counts(int exactGlobalId, int exactStopCode, int coordinateChecked, int coordinateUnique, int ambiguousOrUnmatched) {
        Counts() { this(0, 0, 0, 0, 0); }
        Counts with(SourceMatchMethod method) { return switch (method) { case EXACT_GLOBAL_ID -> new Counts(exactGlobalId + 1, exactStopCode, coordinateChecked, coordinateUnique, ambiguousOrUnmatched); case EXACT_STOP_CODE -> new Counts(exactGlobalId, exactStopCode + 1, coordinateChecked, coordinateUnique, ambiguousOrUnmatched); case COORDINATE_NAME_CHECKED -> new Counts(exactGlobalId, exactStopCode, coordinateChecked + 1, coordinateUnique, ambiguousOrUnmatched); case COORDINATE_UNIQUE -> new Counts(exactGlobalId, exactStopCode, coordinateChecked, coordinateUnique + 1, ambiguousOrUnmatched); }; }
        Counts withAmbiguousOrUnmatched() { return new Counts(exactGlobalId, exactStopCode, coordinateChecked, coordinateUnique, ambiguousOrUnmatched + 1); }
    }

    private static final class SourceStation {
        private final String stationId;
        private final List<SourceStop> members = new ArrayList<>();
        private SourceStation(String stationId) { this.stationId = stationId; }
        void add(SourceStop stop) { members.add(stop); }
        String stationId() { return stationId; }
        List<SourceStop> members() { return List.copyOf(members); }
    }

    private static final class RuntimeIndex {
        private final Map<String, List<RuntimeStop>> byStopId = new HashMap<>();
        private final Map<String, List<RuntimeStop>> byStopCode = new HashMap<>();
        private final Map<String, List<RuntimeStop>> byNormalizedName = new HashMap<>();
        private final Map<CoordinateCell, List<RuntimeStop>> byCoordinateCell = new HashMap<>();
        void add(RuntimeStop stop) {
            byStopId.computeIfAbsent(stop.stopId(), ignored -> new ArrayList<>()).add(stop);
            if (stop.stopCode() != null) byStopCode.computeIfAbsent(stop.stopCode(), ignored -> new ArrayList<>()).add(stop);
            if (stop.normalizedName() != null && !stop.normalizedName().isBlank()) byNormalizedName.computeIfAbsent(stop.normalizedName(), ignored -> new ArrayList<>()).add(stop);
            if (stop.latitude() != null && stop.longitude() != null) byCoordinateCell.computeIfAbsent(CoordinateCell.of(stop.latitude(), stop.longitude()), ignored -> new ArrayList<>()).add(stop);
        }
        Map<String, List<RuntimeStop>> byStopId() { return byStopId; }
        Map<String, List<RuntimeStop>> byStopCode() { return byStopCode; }
        Map<String, List<RuntimeStop>> byNormalizedName() { return byNormalizedName; }
        Map<CoordinateCell, List<RuntimeStop>> byCoordinateCell() { return byCoordinateCell; }
    }

    private record CoordinateCell(int latitudeCell, int longitudeCell) {
        static CoordinateCell of(double latitude, double longitude) {
            return new CoordinateCell((int) Math.floor(latitude / COORDINATE_GRID_DEGREES), (int) Math.floor(longitude / COORDINATE_GRID_DEGREES));
        }
    }

    public record BuildResult(int sourceStationCount, int exactGlobalIdCount, int exactStopCodeCount, int coordinateNameCheckedCount, int coordinateUniqueCount, int ambiguousOrUnmatchedCount, long elapsedMs, long bytes) {}
}
