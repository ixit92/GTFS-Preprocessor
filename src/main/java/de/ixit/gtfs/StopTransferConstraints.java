package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.TransferRule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unscoped lower bounds only. A consumer must still resolve route/trip/service rules. */
final class StopTransferConstraints {
    private final Map<Pair, Constraint> constraints = new HashMap<>();
    private final Map<String, Stop> stops = new HashMap<>();

    StopTransferConstraints(List<Stop> stops, List<TransferRule> rules) {
        for (Stop stop : stops) this.stops.put(stop.stopId(), stop);
        for (TransferRule rule : rules) {
            if (!"GTFS_TRANSFERS".equals(rule.source()) || !"STOP".equals(rule.scopeType())) continue;
            if (!rule.pedestrianUsable() && !"PROHIBITED".equals(rule.transferSemantic())) continue;
            Integer minimum = rule.minTransferTimeSeconds();
            boolean invalidMinimum = minimum != null && minimum < 0
                    || "MINIMUM_TIME".equals(rule.transferSemantic()) && minimum == null;
            Constraint value = new Constraint("PROHIBITED".equals(rule.transferSemantic()) || invalidMinimum,
                    minimum != null && minimum >= 0 ? minimum : null);
            constraints.merge(new Pair(rule.fromStopId(), rule.toStopId()), value, Constraint::merge);
        }
    }

    Constraint between(String from, String to) {
        Constraint result = Constraint.NONE;
        for (String fromId : endpoints(from)) {
            for (String toId : endpoints(to)) {
                result = result.merge(constraints.getOrDefault(new Pair(fromId, toId), Constraint.NONE));
            }
        }
        return result;
    }

    private List<String> endpoints(String id) {
        Stop stop = stops.get(id);
        Stop parent = stop == null ? null : stops.get(stop.parentStation());
        return parent != null && Integer.valueOf(1).equals(parent.locationType())
                ? List.of(id, parent.stopId()) : List.of(id);
    }

    record Constraint(boolean blocked, Integer minimumSeconds) {
        static final Constraint NONE = new Constraint(false, null);

        Constraint merge(Constraint other) {
            Integer minimum = minimumSeconds;
            if (minimum == null) minimum = other.minimumSeconds;
            else if (other.minimumSeconds != null) minimum = Math.max(minimum, other.minimumSeconds);
            return new Constraint(blocked || other.blocked, minimum);
        }
    }

    private record Pair(String from, String to) {
    }
}
