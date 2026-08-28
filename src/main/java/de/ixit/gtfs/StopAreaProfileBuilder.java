package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaProfile;

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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class StopAreaProfileBuilder {
    private static final int MAX_LABELS = 32;
    private static final int PROFILE_RELEASE_ROWS = 50_000;

    private final Map<String, AreaAccumulator> areasById = new HashMap<>();

    public StopAreaProfileBuilder(List<Stop> stops, List<StopArea> stopAreas) {
        for (StopArea area : stopAreas) {
            areasById.put(area.areaId(), new AreaAccumulator(area.areaId(), area.areaName(), area.stopCount()));
        }
        for (Stop stop : stops) {
            String areaId = StopAreaBuilder.areaIdFor(stop);
            AreaAccumulator accumulator = areasById.computeIfAbsent(
                    areaId,
                    ignored -> new AreaAccumulator(areaId, stop.stopName(), 0)
            );
            accumulator.inspectName(stop.stopName());
            if (stop.platformCode() != null && !stop.platformCode().isBlank()) {
                accumulator.addPlatformCode(stop.platformCode().trim());
            }
        }
    }

    public StopAreaProfileBuildResult buildFromDatabase(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT
                         area_id,
                         route_id,
                         route_type,
                         line_label,
                         stop_time_count,
                         trip_count
                     FROM area_route_service_summary
                     ORDER BY area_id, route_type, line_label, route_id
                     """)) {
            while (resultSet.next()) {
                AreaAccumulator accumulator = areasById.get(resultSet.getString("area_id"));
                if (accumulator == null) {
                    continue;
                }
                accumulator.addRouteId(resultSet.getString("route_id"));
                accumulator.stopTimeCount += resultSet.getInt("stop_time_count");
                accumulator.tripCount += resultSet.getInt("trip_count");
                Integer routeType = (Integer) resultSet.getObject("route_type");
                String lineLabel = resultSet.getString("line_label");
                accumulator.addRouteSignal(routeType, lineLabel);
            }
        }

        List<StopAreaProfile> profiles = new ArrayList<>(areasById.size());
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
        profiles.sort(Comparator.comparing(StopAreaProfile::areaId));
        return new StopAreaProfileBuildResult(Collections.unmodifiableList(profiles));
    }

    private static final class AreaAccumulator {
        private final String areaId;
        private final int stopCount;
        private Set<String> platformCodes;
        private Set<Integer> routeTypes;
        private Set<String> lineLabels;
        private int routeCount;
        private int tripCount;
        private int stopTimeCount;
        private boolean hasRailService;
        private boolean hasTrain;
        private boolean hasSubway;
        private boolean hasTram;
        private boolean hasBus;
        private boolean stationNameSignal;
        private boolean mainStationSignal;

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
                mainStationSignal = true;
                stationNameSignal = true;
            }
            if (containsToken(normalized, "bf")
                    || normalized.contains("bahnhof")
                    || normalized.contains("koelnmesse")
                    || normalized.contains("messe")
                    || normalized.contains("suedkreuz")
                    || normalized.contains("ostbahnhof")
                    || normalized.contains("westbahnhof")
                    || normalized.contains("nordbahnhof")) {
                stationNameSignal = true;
            }
        }

        private void addRouteSignal(Integer routeType, String lineLabel) {
            if (routeType != null) {
                if (routeTypes == null) {
                    routeTypes = new HashSet<>();
                }
                routeTypes.add(routeType);
                hasTrain = hasTrain || isTrainRouteType(routeType);
                hasSubway = hasSubway || isSubwayRouteType(routeType);
                hasTram = hasTram || isTramRouteType(routeType);
                hasBus = hasBus || isBusRouteType(routeType);
                hasRailService = hasRailService || isTrainRouteType(routeType);
            }
            if (lineLabel != null && !lineLabel.isBlank() && sizeOf(lineLabels) < MAX_LABELS) {
                String trimmed = lineLabel.trim();
                if (lineLabels == null) {
                    lineLabels = new LinkedHashSet<>();
                }
                lineLabels.add(trimmed);
                if (routeType == null) {
                    hasRailService = hasRailService || isRailLineLabel(trimmed);
                }
            }
        }

        private void addRouteId(String routeId) {
            if (routeId != null && !routeId.isBlank()) {
                // The source table is keyed by (area_id, route_id).
                routeCount++;
            }
        }

        private void addPlatformCode(String platformCode) {
            if (platformCodes == null) {
                platformCodes = new HashSet<>();
            }
            platformCodes.add(platformCode);
        }

        private StopAreaProfile toProfile() {
            boolean busOnly = hasBus && !hasRailService && !hasSubway && !hasTram;
            String profileClass = classify(busOnly);
            int score = searchPriorityScore(busOnly);
            return new StopAreaProfile(
                    areaId,
                    profileClass,
                    stopCount,
                    sizeOf(platformCodes),
                    routeCount,
                    tripCount,
                    stopTimeCount,
                    joinIntegers(routeTypes),
                    joinStrings(lineLabels),
                    hasRailService,
                    hasTrain,
                    hasSubway,
                    hasTram,
                    hasBus,
                    busOnly,
                    stationNameSignal,
                    mainStationSignal,
                    score,
                    explanation(profileClass, score, busOnly)
            );
        }

        private String classify(boolean busOnly) {
            if (hasRailService && mainStationSignal) {
                return "MAIN_RAIL";
            }
            if (hasRailService) {
                return "RAIL";
            }
            if (hasSubway || hasTram) {
                return "URBAN_RAIL";
            }
            if (busOnly) {
                return "BUS_ONLY";
            }
            if (routeCount > 0) {
                return "MIXED";
            }
            return "NO_SERVICE";
        }

        private int searchPriorityScore(boolean busOnly) {
            int score = stopCount * 8
                    + sizeOf(platformCodes) * 20
                    + routeCount * 60
                    + Math.min(tripCount / 5, 200)
                    + Math.min(stopTimeCount / 20, 120);
            if (stationNameSignal) {
                score += 250;
            }
            if (mainStationSignal) {
                score += 400;
            }
            if (hasRailService) {
                score += 800;
            }
            if (busOnly) {
                score -= 250;
            }
            return score;
        }

        private String explanation(String profileClass, int score, boolean busOnly) {
            StringBuilder explanation = new StringBuilder(128)
                    .append("class=").append(profileClass)
                    .append("; stops=").append(stopCount)
                    .append("; platforms=").append(sizeOf(platformCodes))
                    .append("; routes=").append(routeCount)
                    .append("; trips=").append(tripCount)
                    .append("; stop_times=").append(stopTimeCount)
                    .append("; score=").append(score);
            if (hasRailService) {
                explanation.append("; rail_service");
            }
            if (busOnly) {
                explanation.append("; bus_only");
            }
            if (mainStationSignal) {
                explanation.append("; main_station_name");
            } else if (stationNameSignal) {
                explanation.append("; station_name");
            }
            return explanation.toString();
        }

        private static boolean containsToken(String normalized, String token) {
            return (" " + normalized + " ").contains(" " + token + " ");
        }
    }

    private static boolean isTrainRouteType(int routeType) {
        return routeType == 2 || (routeType >= 100 && routeType <= 109);
    }

    private static boolean isSubwayRouteType(int routeType) {
        return routeType == 1 || routeType == 400 || routeType == 401;
    }

    private static boolean isTramRouteType(int routeType) {
        return routeType == 0 || routeType == 900 || routeType == 901;
    }

    private static boolean isBusRouteType(int routeType) {
        return routeType == 3 || (routeType >= 700 && routeType <= 709);
    }

    private static boolean isRailLineLabel(String lineLabel) {
        String normalized = lineLabel.trim().toUpperCase(Locale.ROOT);
        return normalized.matches("^(ICE|IC|EC|ECE|RE|RB)\\s?.*")
                || normalized.matches("^S\\d.*");
    }

    private static String joinIntegers(Set<Integer> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<Integer> sorted = values.stream().sorted().toList();
        StringBuilder joined = new StringBuilder(sorted.size() * 4);
        for (Integer value : sorted) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(value);
        }
        return joined.toString();
    }

    private static String joinStrings(Set<String> values) {
        return values == null || values.isEmpty() ? "" : String.join(",", values);
    }

    private static int sizeOf(Set<?> values) {
        return values == null ? 0 : values.size();
    }

    public record StopAreaProfileBuildResult(List<StopAreaProfile> profiles) {
    }
}
