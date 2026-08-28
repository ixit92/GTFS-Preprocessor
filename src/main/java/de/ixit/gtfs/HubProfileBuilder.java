package de.ixit.gtfs;

import de.ixit.gtfs.model.HubProfile;
import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.Trip;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HubProfileBuilder {
    private static final int PROFILE_RELEASE_ROWS = 50_000;

    private final Map<String, AreaAccumulator> areasById = new HashMap<>();
    private final Map<String, String> areaIdByStopId = new HashMap<>();
    private final boolean readTransferReferencesFromDatabase;

    public HubProfileBuilder(List<Stop> stops, List<StopArea> stopAreas, List<Route> routes, List<Trip> trips) {
        this(stops, stopAreas, false);
    }

    public static HubProfileBuilder databaseBacked(List<Stop> stops, List<StopArea> stopAreas) {
        return new HubProfileBuilder(stops, stopAreas, true);
    }

    private HubProfileBuilder(List<Stop> stops, List<StopArea> stopAreas, boolean readTransferReferencesFromDatabase) {
        this.readTransferReferencesFromDatabase = readTransferReferencesFromDatabase;
        for (StopArea area : stopAreas) {
            AreaAccumulator accumulator = new AreaAccumulator(area.areaId(), area.areaName(), area.stopCount());
            areasById.put(area.areaId(), accumulator);
        }
        for (Stop stop : stops) {
            String areaId = StopAreaBuilder.areaIdFor(stop);
            if (!readTransferReferencesFromDatabase) {
                areaIdByStopId.put(stop.stopId(), areaId);
            }
            areasById.computeIfAbsent(areaId, ignored -> new AreaAccumulator(areaId, stop.stopName(), 0))
                    .inspectName(stop.stopName());
        }
    }

    public void observeStopTime(String tripId, String stopId, int stopSequence) {
        // Keep stop_times heap-stable; metrics are read from SQLite after import.
    }

    public void observeTransfer(String fromStopId, String toStopId) {
        markTransferCandidate(fromStopId);
        if (!fromStopId.equals(toStopId)) {
            markTransferCandidate(toStopId);
        }
    }

    public HubProfileBuildResult build() {
        List<HubProfile> profiles = new ArrayList<>(areasById.size());
        var iterator = areasById.values().iterator();
        int converted = 0;
        while (iterator.hasNext()) {
            profiles.add(iterator.next().toProfile());
            iterator.remove();
            converted++;
            if (converted % PROFILE_RELEASE_ROWS == 0) {
                System.gc();
            }
        }
        profiles.sort(Comparator.comparing(HubProfile::areaId));
        areaIdByStopId.clear();
        HubProfileStats stats = HubProfileStats.from(profiles);
        System.gc();
        return new HubProfileBuildResult(Collections.unmodifiableList(profiles), stats);
    }

    public HubProfileBuildResult buildFromDatabase(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT area_id,
                            SUM(stop_time_count) AS stop_time_count,
                            SUM(trip_count) AS trip_count,
                            COUNT(DISTINCT route_id) AS route_count,
                            COUNT(DISTINCT route_type) AS route_type_count,
                            MAX(CASE WHEN route_type IN (2, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109) THEN 1 ELSE 0 END) AS has_train,
                            MAX(CASE WHEN route_type IN (1, 400, 401) THEN 1 ELSE 0 END) AS has_subway,
                            MAX(CASE WHEN route_type IN (0, 900, 901) THEN 1 ELSE 0 END) AS has_tram,
                            MAX(CASE WHEN route_type IN (3, 700, 701, 702, 703, 704, 705, 706, 707, 708, 709) THEN 1 ELSE 0 END) AS has_bus
                     FROM area_route_service_summary
                     GROUP BY area_id
                     """)) {
            while (resultSet.next()) {
                AreaAccumulator accumulator = areasById.get(resultSet.getString("area_id"));
                if (accumulator == null) {
                    continue;
                }
                accumulator.stopTimeCount = resultSet.getInt("stop_time_count");
                accumulator.tripCount = resultSet.getInt("trip_count");
                accumulator.routeCount = resultSet.getInt("route_count");
                accumulator.routeTypeCount = resultSet.getInt("route_type_count");
                accumulator.hasTrain = accumulator.hasTrain || resultSet.getInt("has_train") == 1;
                accumulator.hasSubway = accumulator.hasSubway || resultSet.getInt("has_subway") == 1;
                accumulator.hasTram = accumulator.hasTram || resultSet.getInt("has_tram") == 1;
                accumulator.hasBus = accumulator.hasBus || resultSet.getInt("has_bus") == 1;
            }
        }
        if (readTransferReferencesFromDatabase) {
            readTransferReferences(databasePath);
        }
        return build();
    }

    private void readTransferReferences(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT members.area_id, COUNT(*) AS transfer_reference_count
                     FROM (
                         SELECT from_stop_id AS stop_id FROM transfers
                         UNION ALL
                         SELECT to_stop_id AS stop_id
                         FROM transfers
                         WHERE to_stop_id <> from_stop_id
                     ) references_by_stop
                     JOIN stop_area_members members ON members.stop_id = references_by_stop.stop_id
                     GROUP BY members.area_id
                     """)) {
            while (resultSet.next()) {
                AreaAccumulator accumulator = areasById.get(resultSet.getString("area_id"));
                if (accumulator != null) {
                    accumulator.transferReferenceCount = resultSet.getInt("transfer_reference_count");
                }
            }
        }
    }

    private void markTransferCandidate(String stopId) {
        String areaId = areaIdByStopId.get(stopId);
        if (areaId != null && areasById.containsKey(areaId)) {
            areasById.get(areaId).transferReferenceCount++;
        }
    }

    private static final class AreaAccumulator {
        private final String areaId;
        private final int stopCount;
        private int routeCount;
        private int tripCount;
        private int routeTypeCount;
        private boolean hasTrain;
        private boolean hasSubway;
        private boolean hasTram;
        private boolean hasBus;
        private boolean hasRailKeyword;
        private boolean hasMainStationKeyword;
        private int stopTimeCount;
        private int transferReferenceCount;

        private AreaAccumulator(String areaId, String areaName, int stopCount) {
            this.areaId = areaId;
            this.stopCount = stopCount;
            inspectName(areaName);
        }

        private void inspectName(String name) {
            String normalized = StopNameNormalizer.normalize(name);
            if (normalized.isBlank()) {
                return;
            }
            if (containsToken(normalized, "hbf") || normalized.contains("hauptbahnhof")) {
                hasMainStationKeyword = true;
                hasRailKeyword = true;
            }
            if (containsToken(normalized, "bf") || normalized.contains("bahnhof")) {
                hasRailKeyword = true;
            }
        }

        private HubProfile toProfile() {
            int transferCandidateScore = transferCandidateScore(routeCount, tripCount, routeTypeCount);
            HubLevel hubLevel = classify(routeCount, tripCount, routeTypeCount, transferCandidateScore);
            String explanation = explain(routeCount, tripCount, routeTypeCount, transferCandidateScore, hubLevel);
            return new HubProfile(
                    areaId,
                    hubLevel.name(),
                    stopCount,
                    routeCount,
                    tripCount,
                    routeTypeCount,
                    stopTimeCount,
                    hasTrain,
                    hasSubway,
                    hasTram,
                    hasBus,
                    hasRailKeyword,
                    hasMainStationKeyword,
                    transferCandidateScore,
                    explanation
            );
        }

        private int transferCandidateScore(int routeCount, int tripCount, int routeTypeCount) {
            int score = stopCount * 2
                    + routeCount * 5
                    + routeTypeCount * 6
                    + Math.min(tripCount / 10, 30)
                    + transferReferenceCount * 3;
            if (hasRailKeyword) {
                score += 10;
            }
            if (hasMainStationKeyword) {
                score += 20;
            }
            return score;
        }

        private HubLevel classify(int routeCount, int tripCount, int routeTypeCount, int score) {
            if (hasMainStationKeyword || routeCount >= 20 || tripCount >= 500 || score >= 120) {
                return HubLevel.MAIN_STATION_CANDIDATE;
            }
            if (routeCount >= 10 || tripCount >= 250 || routeTypeCount >= 3 || score >= 80) {
                return HubLevel.LARGE;
            }
            if (routeCount >= 3 || tripCount >= 50 || (routeTypeCount >= 2 && transferReferenceCount > 0) || score >= 35) {
                return HubLevel.MEDIUM;
            }
            if (routeCount > 1 || stopCount > 1 || transferReferenceCount > 0 || score >= 15) {
                return HubLevel.SMALL;
            }
            return HubLevel.NONE;
        }

        private String explain(int routeCount, int tripCount, int routeTypeCount, int score, HubLevel hubLevel) {
            StringBuilder explanation = new StringBuilder(144)
                    .append("level=").append(hubLevel)
                    .append("; stops=").append(stopCount)
                    .append("; routes=").append(routeCount)
                    .append("; trips=").append(tripCount)
                    .append("; route_types=").append(routeTypeCount)
                    .append("; stop_times=").append(stopTimeCount)
                    .append("; transfer_refs=").append(transferReferenceCount)
                    .append("; score=").append(score);
            if (hasMainStationKeyword) {
                explanation.append("; main_station_keyword");
            } else if (hasRailKeyword) {
                explanation.append("; rail_keyword");
            }
            return explanation.toString();
        }

        private static boolean containsToken(String normalized, String token) {
            return (" " + normalized + " ").contains(" " + token + " ");
        }
    }

    public record HubProfileBuildResult(List<HubProfile> profiles, HubProfileStats stats) {
    }

    public record HubProfileStats(
            int profileCount,
            Map<String, Integer> levelCounts,
            List<HubProfile> topHubs,
            List<HubProfile> mainStationCandidates
    ) {
        public static HubProfileStats from(List<HubProfile> profiles) {
            Map<String, Integer> levelCounts = new LinkedHashMap<>();
            for (HubLevel level : HubLevel.values()) {
                levelCounts.put(level.name(), 0);
            }
            for (HubProfile profile : profiles) {
                levelCounts.merge(profile.hubLevel(), 1, Integer::sum);
            }

            List<HubProfile> topHubs = profiles.stream()
                    .sorted(Comparator.comparingInt(HubProfile::routeCount).reversed()
                            .thenComparing(Comparator.comparingInt(HubProfile::tripCount).reversed())
                            .thenComparing(HubProfile::areaId))
                    .limit(10)
                    .toList();
            List<HubProfile> mainStationCandidates = profiles.stream()
                    .filter(profile -> HubLevel.MAIN_STATION_CANDIDATE.name().equals(profile.hubLevel()))
                    .sorted(Comparator.comparingInt(HubProfile::routeCount).reversed()
                            .thenComparing(Comparator.comparingInt(HubProfile::tripCount).reversed())
                            .thenComparing(HubProfile::areaId))
                    .toList();
            return new HubProfileStats(profiles.size(), new LinkedHashMap<>(levelCounts), List.copyOf(topHubs), List.copyOf(mainStationCandidates));
        }
    }
}
