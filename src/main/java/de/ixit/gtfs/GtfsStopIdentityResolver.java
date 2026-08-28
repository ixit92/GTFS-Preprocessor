package de.ixit.gtfs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class GtfsStopIdentityResolver {
    private static final int MAX_NAME_MATCH_DISTANCE_METERS = 600;
    private static final int MAX_CODE_MATCH_DISTANCE_METERS = 2_000;
    private static final Pattern PLATFORM_SUFFIX = Pattern.compile(
            "\\b(?:gleis|platform|plattform|quai|quay|perron)\\s*[a-z0-9-]*\\b"
    );
    private static final Set<String> GENERIC_STATION_TOKENS = Set.of(
            "bahnhof", "bf", "hbf", "station", "gare", "stazione", "staziun", "halt", "haltestelle"
    );

    public Map<StopKey, String> resolve(List<RawStop> rawStops) {
        List<RawStop> stops = rawStops.stream()
                .sorted(Comparator.comparingInt(RawStop::sourcePriority)
                        .thenComparing(RawStop::sourceId)
                        .thenComparing(RawStop::stopId))
                .toList();
        Map<StopKey, RawStop> byKey = new LinkedHashMap<>();
        stops.forEach(stop -> byKey.put(stop.key(), stop));

        Map<StopKey, StopKey> anchorByStop = new LinkedHashMap<>();
        for (RawStop stop : stops) {
            anchorByStop.put(stop.key(), findAnchor(stop, byKey));
        }
        Map<StopKey, RawStop> anchors = new LinkedHashMap<>();
        anchorByStop.values().forEach(key -> anchors.putIfAbsent(key, byKey.get(key)));

        UnionFind unionFind = new UnionFind(anchors.keySet());
        Map<String, List<RawStop>> byName = new HashMap<>();
        Map<String, List<RawStop>> byCode = new HashMap<>();
        for (RawStop anchor : anchors.values()) {
            String nameKey = stationNameKey(anchor.stopName());
            String codeKey = codeKey(anchor.stopCode());
            Set<RawStop> candidates = new LinkedHashSet<>();
            if (!nameKey.isBlank()) {
                candidates.addAll(byName.getOrDefault(nameKey, List.of()));
            }
            if (!codeKey.isBlank()) {
                candidates.addAll(byCode.getOrDefault(codeKey, List.of()));
            }
            for (RawStop candidate : candidates) {
                if (!anchor.sourceId().equals(candidate.sourceId()) && sameStation(anchor, candidate)) {
                    unionFind.union(anchor.key(), candidate.key());
                }
            }
            if (!nameKey.isBlank()) {
                byName.computeIfAbsent(nameKey, ignored -> new ArrayList<>()).add(anchor);
            }
            if (!codeKey.isBlank()) {
                byCode.computeIfAbsent(codeKey, ignored -> new ArrayList<>()).add(anchor);
            }
        }

        Map<StopKey, List<StopKey>> membersByRoot = new LinkedHashMap<>();
        anchors.keySet().forEach(key -> membersByRoot
                .computeIfAbsent(unionFind.find(key), ignored -> new ArrayList<>())
                .add(key));
        Map<StopKey, String> canonicalByAnchor = new LinkedHashMap<>();
        for (List<StopKey> members : membersByRoot.values()) {
            members.sort(Comparator.comparing(StopKey::namespacedId));
            long sourceCount = members.stream().map(StopKey::sourceId).distinct().count();
            String canonicalKey = sourceCount > 1
                    ? "C:" + shortHash(members.stream().map(StopKey::namespacedId).toList())
                    : "S:" + members.get(0).namespacedId();
            members.forEach(member -> canonicalByAnchor.put(member, canonicalKey));
        }

        Map<StopKey, String> result = new LinkedHashMap<>();
        anchorByStop.forEach((stop, anchor) -> result.put(stop, canonicalByAnchor.get(anchor)));
        return Map.copyOf(result);
    }

    private static StopKey findAnchor(RawStop stop, Map<StopKey, RawStop> byKey) {
        RawStop current = stop;
        Set<StopKey> visited = new LinkedHashSet<>();
        while (current.parentStation() != null && !current.parentStation().isBlank()) {
            if (!visited.add(current.key())) {
                return stop.key();
            }
            RawStop parent = byKey.get(new StopKey(current.sourceId(), current.parentStation()));
            if (parent == null) {
                return current.key();
            }
            current = parent;
        }
        return current.key();
    }

    private static boolean sameStation(RawStop left, RawStop right) {
        String leftCode = codeKey(left.stopCode());
        String rightCode = codeKey(right.stopCode());
        boolean sameCode = !leftCode.isBlank() && leftCode.equals(rightCode);
        String leftName = stationNameKey(left.stopName());
        String rightName = stationNameKey(right.stopName());
        boolean sameName = !leftName.isBlank()
                && leftName.equals(rightName)
                && hasDistinctiveToken(leftName);
        if (!sameCode && !sameName) {
            return false;
        }
        Integer distance = distanceMeters(left, right);
        if (distance == null) {
            return sameCode && sameName;
        }
        return distance <= (sameCode ? MAX_CODE_MATCH_DISTANCE_METERS : MAX_NAME_MATCH_DISTANCE_METERS);
    }

    static String stationNameKey(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        normalized = PLATFORM_SUFFIX.matcher(normalized).replaceAll(" ");
        List<String> tokens = new ArrayList<>();
        for (String token : normalized.split("\\s+")) {
            if (!token.isBlank() && !GENERIC_STATION_TOKENS.contains(token)) {
                tokens.add(token);
            }
        }
        return String.join(" ", tokens).replaceAll("\\s+", " ").trim();
    }

    private static boolean hasDistinctiveToken(String normalizedName) {
        return normalizedName.split("\\s+").length > 0
                && !GENERIC_STATION_TOKENS.contains(normalizedName);
    }

    private static String codeKey(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Integer distanceMeters(RawStop left, RawStop right) {
        if (left.latitude() == null || left.longitude() == null
                || right.latitude() == null || right.longitude() == null) {
            return null;
        }
        double lat1 = Math.toRadians(left.latitude());
        double lat2 = Math.toRadians(right.latitude());
        double deltaLat = Math.toRadians(right.latitude() - left.latitude());
        double deltaLon = Math.toRadians(right.longitude() - left.longitude());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return (int) Math.round(6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a)));
    }

    private static String shortHash(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            values.forEach(value -> {
                digest.update(value.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            });
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder();
            for (int index = 0; index < 10; index++) {
                result.append(String.format(Locale.ROOT, "%02x", hash[index]));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record StopKey(String sourceId, String stopId) {
        public StopKey {
            requireText(sourceId, "sourceId");
            requireText(stopId, "stopId");
        }

        public String namespacedId() {
            return sourceId + "::" + stopId;
        }
    }

    public record RawStop(
            String sourceId,
            int sourcePriority,
            String stopId,
            String stopCode,
            String stopName,
            Double latitude,
            Double longitude,
            String parentStation,
            Integer locationType
    ) {
        public RawStop {
            requireText(sourceId, "sourceId");
            requireText(stopId, "stopId");
            stopCode = stopCode == null ? "" : stopCode;
            stopName = stopName == null ? "" : stopName;
            parentStation = parentStation == null ? "" : parentStation;
        }

        public StopKey key() {
            return new StopKey(sourceId, stopId);
        }
    }

    private static final class UnionFind {
        private final Map<StopKey, StopKey> parent = new HashMap<>();

        private UnionFind(Set<StopKey> keys) {
            keys.forEach(key -> parent.put(key, key));
        }

        private StopKey find(StopKey key) {
            StopKey current = parent.get(key);
            if (current.equals(key)) {
                return current;
            }
            StopKey root = find(current);
            parent.put(key, root);
            return root;
        }

        private void union(StopKey left, StopKey right) {
            StopKey leftRoot = find(left);
            StopKey rightRoot = find(right);
            if (!leftRoot.equals(rightRoot)) {
                StopKey primary = leftRoot.namespacedId().compareTo(rightRoot.namespacedId()) <= 0
                        ? leftRoot : rightRoot;
                StopKey secondary = primary.equals(leftRoot) ? rightRoot : leftRoot;
                parent.put(secondary, primary);
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
