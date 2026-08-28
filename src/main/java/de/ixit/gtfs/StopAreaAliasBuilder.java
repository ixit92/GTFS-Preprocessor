package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaAlias;
import de.ixit.gtfs.model.StopAreaCity;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class StopAreaAliasBuilder {
    private StopAreaAliasBuilder() {
    }

    public static StopAreaAliasBuildResult build(List<Stop> stops, List<StopArea> stopAreas) {
        return build(stops, stopAreas, List.of());
    }

    public static StopAreaAliasBuildResult build(
            List<Stop> stops,
            List<StopArea> stopAreas,
            List<StopAreaCity> stopAreaCities
    ) {
        Map<String, String> cityByAreaId = new LinkedHashMap<>();
        for (StopAreaCity city : stopAreaCities) {
            if (city.areaId() != null && !city.areaId().isBlank()
                    && city.cityName() != null && !city.cityName().isBlank()) {
                cityByAreaId.putIfAbsent(city.areaId(), city.cityName());
            }
        }
        AliasAccumulator accumulator = new AliasAccumulator();
        for (StopArea area : stopAreas) {
            addAliases(accumulator, area.areaId(), area.areaName(), "AREA_NAME", 100);
            addCityQualifiedAliases(
                    accumulator,
                    area.areaId(),
                    cityByAreaId.get(area.areaId()),
                    area.areaName(),
                    "AREA_NAME",
                    95
            );
        }
        for (Stop stop : stops) {
            String areaId = StopAreaBuilder.areaIdFor(stop);
            addAliases(accumulator, areaId, stop.stopName(), "STOP_NAME", 70);
            addCityQualifiedAliases(
                    accumulator,
                    areaId,
                    cityByAreaId.get(areaId),
                    stop.stopName(),
                    "STOP_NAME",
                    65
            );
        }
        List<StopAreaAlias> aliases = accumulator.aliases.values().stream()
                .sorted(Comparator
                        .comparing(StopAreaAlias::areaId)
                        .thenComparing((StopAreaAlias alias) -> -alias.priority())
                        .thenComparing(StopAreaAlias::aliasNormalized)
                        .thenComparing(StopAreaAlias::aliasType))
                .toList();
        List<StopAreaAlias> result = List.copyOf(aliases);
        int duplicateAliasCount = accumulator.duplicateAliasCount;
        accumulator.aliases.clear();
        cityByAreaId.clear();
        System.gc();
        return new StopAreaAliasBuildResult(result, duplicateAliasCount);
    }

    private static void addCityQualifiedAliases(
            AliasAccumulator accumulator,
            String areaId,
            String city,
            String name,
            String source,
            int priority
    ) {
        String normalizedCity = StopNameNormalizer.normalize(city);
        String normalizedName = StopNameNormalizer.normalize(name);
        if (normalizedCity.isBlank() || normalizedName.isBlank()
                || normalizedName.equals(normalizedCity)
                || normalizedName.startsWith(normalizedCity + " ")) {
            return;
        }
        accumulator.addNormalized(
                areaId,
                normalizedCity + " " + normalizedName,
                "CITY_QUALIFIED",
                source,
                priority
        );
    }

    private static void addAliases(AliasAccumulator accumulator, String areaId, String name, String source, int basePriority) {
        String normalized = StopNameNormalizer.normalize(name);
        if (normalized.isBlank()) {
            return;
        }
        accumulator.add(areaId, name.trim(), normalized, "CANONICAL", source, basePriority);
        addStationAliases(accumulator, areaId, normalized, source, basePriority - 10);
        addPrefixAliases(accumulator, areaId, normalized, source, basePriority - 20);
        addQualifierAliases(accumulator, areaId, normalized, source, basePriority - 30);
    }

    private static void addStationAliases(AliasAccumulator accumulator, String areaId, String normalized, String source, int priority) {
        if (containsToken(normalized, "hbf")) {
            accumulator.addNormalized(areaId, normalized.replace(" hbf", " hauptbahnhof").replace("hbf ", "hauptbahnhof "), "STATION_SYNONYM", source, priority);
        }
        if (normalized.contains("hauptbahnhof")) {
            accumulator.addNormalized(areaId, normalized.replace("hauptbahnhof", "hbf"), "STATION_SYNONYM", source, priority);
        }
        if (containsToken(normalized, "bf")) {
            accumulator.addNormalized(areaId, normalized.replace(" bf", " bahnhof").replace("bf ", "bahnhof "), "STATION_SYNONYM", source, priority);
        }
        if (normalized.contains("bahnhof")) {
            accumulator.addNormalized(areaId, normalized.replace("bahnhof", "bf"), "STATION_SYNONYM", source, priority);
        }
        if (normalized.contains("stadtbahnhof")) {
            accumulator.addNormalized(areaId, normalized.replace("stadtbahnhof", "bahnhof"), "RAIL_STATION_INTENT", source, priority);
            accumulator.addNormalized(areaId, normalized.replace("stadtbahnhof", "bf"), "RAIL_STATION_INTENT", source, priority);
        }
        addCityStationAliases(accumulator, areaId, normalized, source, priority - 5);
    }

    private static void addPrefixAliases(AliasAccumulator accumulator, String areaId, String normalized, String source, int priority) {
        String stripped = normalized
                .replaceFirst("^(s und u|s u|s|u) ", "")
                .replaceFirst("^(s und u|s u|s|u)$", "");
        if (!stripped.isBlank() && !stripped.equals(normalized)) {
            accumulator.addNormalized(areaId, stripped, "MODE_PREFIX_STRIPPED", source, priority);
        }
    }

    private static void addQualifierAliases(AliasAccumulator accumulator, String areaId, String normalized, String source, int priority) {
        if (normalized.endsWith(" bf")) {
            accumulator.addNormalized(areaId, normalized.substring(0, normalized.length() - 3).trim(), "STATION_SUFFIX_STRIPPED", source, priority);
        }
        if (normalized.endsWith(" bahnhof")) {
            accumulator.addNormalized(areaId, normalized.substring(0, normalized.length() - 8).trim(), "STATION_SUFFIX_STRIPPED", source, priority);
        }
    }

    private static void addCityStationAliases(AliasAccumulator accumulator, String areaId, String normalized, String source, int priority) {
        String city = cityCandidate(normalized);
        if (city.isBlank()) {
            return;
        }
        if (containsToken(city, "hbf") || city.contains("hauptbahnhof") || city.contains("bahnhof") || containsToken(city, "bf")) {
            return;
        }
        boolean stationLike = containsToken(normalized, "hbf")
                || normalized.contains("hauptbahnhof")
                || containsToken(normalized, "bf")
                || normalized.contains("bahnhof")
                || normalized.contains("stadtbahnhof");
        if (!stationLike) {
            return;
        }
        accumulator.addNormalized(areaId, city + " bahnhof", "RAIL_STATION_INTENT", source, priority);
        accumulator.addNormalized(areaId, city + " bf", "RAIL_STATION_INTENT", source, priority);
        if (normalized.contains("hauptbahnhof") || containsToken(normalized, "hbf")) {
            accumulator.addNormalized(areaId, city + " hbf", "RAIL_STATION_INTENT", source, priority);
            accumulator.addNormalized(areaId, city + " hauptbahnhof", "RAIL_STATION_INTENT", source, priority);
        }
        addPostposedCityStationAliases(accumulator, areaId, normalized, source, priority - 5);
    }

    private static void addPostposedCityStationAliases(
            AliasAccumulator accumulator,
            String areaId,
            String normalized,
            String source,
            int priority
    ) {
        String[] parts = normalized.split(" ");
        for (int index = 0; index < parts.length - 1; index++) {
            String stationToken = parts[index];
            if (!isStationToken(stationToken)) {
                continue;
            }
            String trailingCity = trailingCityCandidate(parts, index + 1);
            if (trailingCity.isBlank() || containsToken(trailingCity, "bahnhof") || containsToken(trailingCity, "bf")) {
                continue;
            }
            accumulator.addNormalized(areaId, trailingCity + " bahnhof", "RAIL_STATION_INTENT", source, priority);
            accumulator.addNormalized(areaId, trailingCity + " bf", "RAIL_STATION_INTENT", source, priority);
            String shortCity = stripTrailingRegionQualifier(trailingCity);
            if (!shortCity.equals(trailingCity) && !shortCity.isBlank()) {
                accumulator.addNormalized(areaId, shortCity + " bahnhof", "RAIL_STATION_INTENT", source, priority);
                accumulator.addNormalized(areaId, shortCity + " bf", "RAIL_STATION_INTENT", source, priority);
            }
            if (stationToken.equals("hbf") || stationToken.equals("hauptbahnhof")) {
                accumulator.addNormalized(areaId, trailingCity + " hbf", "RAIL_STATION_INTENT", source, priority);
                accumulator.addNormalized(areaId, trailingCity + " hauptbahnhof", "RAIL_STATION_INTENT", source, priority);
                if (!shortCity.equals(trailingCity) && !shortCity.isBlank()) {
                    accumulator.addNormalized(areaId, shortCity + " hbf", "RAIL_STATION_INTENT", source, priority);
                    accumulator.addNormalized(areaId, shortCity + " hauptbahnhof", "RAIL_STATION_INTENT", source, priority);
                }
            }
        }
    }

    private static String trailingCityCandidate(String[] parts, int startIndex) {
        StringBuilder builder = new StringBuilder();
        for (int index = startIndex; index < parts.length; index++) {
            String part = parts[index];
            if (part.isBlank() || isStationToken(part) || part.equals("zob")) {
                break;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part);
        }
        return builder.toString().trim();
    }

    private static String stripTrailingRegionQualifier(String city) {
        if (city.endsWith(" bodensee")) {
            return city.substring(0, city.length() - " bodensee".length()).trim();
        }
        if (city.endsWith(" bay") || city.endsWith(" main") || city.endsWith(" westf")) {
            int lastSpace = city.lastIndexOf(' ');
            if (lastSpace > 0) {
                return city.substring(0, lastSpace).trim();
            }
        }
        return city;
    }

    private static boolean isStationToken(String token) {
        return token.equals("hbf")
                || token.equals("hauptbahnhof")
                || token.equals("bahnhof")
                || token.equals("bf")
                || token.equals("stadtbahnhof");
    }

    private static String cityCandidate(String normalized) {
        String city = normalized
                .replaceAll("\\s+\\([^)]*\\)", "")
                .replaceAll("\\s+stadtbahnhof.*$", "")
                .replaceAll("\\s+hauptbahnhof.*$", "")
                .replaceAll("\\s+hbf.*$", "")
                .replaceAll("\\s+bahnhof.*$", "")
                .replaceAll("\\s+bf.*$", "")
                .replaceAll("\\s+zob.*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (city.contains(" aller")) {
            city = city.substring(0, city.indexOf(" aller")).trim();
        }
        if (city.endsWith(" bay") || city.endsWith(" main") || city.endsWith(" westf")) {
            int lastSpace = city.lastIndexOf(' ');
            if (lastSpace > 0) {
                city = city.substring(0, lastSpace).trim();
            }
        }
        return city;
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static final class AliasAccumulator {
        private final Map<AliasKey, StopAreaAlias> aliases = new LinkedHashMap<>();
        private int duplicateAliasCount;

        private void addNormalized(String areaId, String normalizedAlias, String aliasType, String source, int priority) {
            String normalized = StopNameNormalizer.normalize(normalizedAlias);
            if (normalized.isBlank()) {
                return;
            }
            add(areaId, normalized, normalized, aliasType, source, priority);
        }

        private void add(String areaId, String alias, String normalizedAlias, String aliasType, String source, int priority) {
            String normalized = StopNameNormalizer.normalize(normalizedAlias);
            if (areaId == null || areaId.isBlank() || normalized.isBlank()) {
                return;
            }
            addSingle(areaId, alias, normalized, aliasType, source, priority);
            String streetExpanded = expandStreetAbbreviations(normalized);
            if (!streetExpanded.equals(normalized)) {
                addSingle(areaId, streetExpanded, streetExpanded, aliasType, source, priority);
            }
        }

        private void addSingle(String areaId, String alias, String normalized, String aliasType, String source, int priority) {
            AliasKey key = new AliasKey(areaId, normalized, aliasType, source);
            StopAreaAlias previous = aliases.putIfAbsent(key, new StopAreaAlias(
                    areaId,
                    alias,
                    normalized,
                    aliasType,
                    source,
                    priority
            ));
            if (previous != null) {
                duplicateAliasCount++;
            }
        }

        private static String expandStreetAbbreviations(String normalized) {
            String[] tokens = normalized.split(" ");
            boolean changed = false;
            for (int index = 0; index < tokens.length; index++) {
                if (tokens[index].length() > 3 && tokens[index].endsWith("str")) {
                    tokens[index] = tokens[index] + "asse";
                    changed = true;
                }
            }
            return changed ? String.join(" ", tokens) : normalized;
        }
    }

    private record AliasKey(String areaId, String normalizedAlias, String aliasType, String source) {
    }

    public record StopAreaAliasBuildResult(List<StopAreaAlias> aliases, int duplicateAliasCount) {
    }
}
