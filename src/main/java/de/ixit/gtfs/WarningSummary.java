package de.ixit.gtfs;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WarningSummary {
    private final Map<String, Integer> counts = new LinkedHashMap<>();

    public WarningSummary() {
        counts.put("unknown_stop_references", 0);
        counts.put("unknown_route_references", 0);
        counts.put("invalid_coordinates", 0);
        counts.put("empty_stop_names", 0);
        counts.put("duplicate_tokens", 0);
        counts.put("unmapped_transfers", 0);
        counts.put("trips_without_stop_times", 0);
        counts.put("route_axes_too_short", 0);
        counts.put("stop_areas_too_large", 0);
        counts.put("transfer_rules_negative_time", 0);
        counts.put("transfer_rules_very_long_time", 0);
    }

    public void set(String key, int value) {
        counts.put(key, Math.max(0, value));
    }

    public void increment(String key) {
        counts.merge(key, 1, Integer::sum);
    }

    public Map<String, Integer> counts() {
        return Map.copyOf(counts);
    }

    public int totalWarnings() {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int criticalWarnings() {
        return counts.getOrDefault("unknown_stop_references", 0)
                + counts.getOrDefault("unknown_route_references", 0);
    }
}
