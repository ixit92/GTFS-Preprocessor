package de.ixit.gtfs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GtfsTripFusionPlanner {
    static final int MAX_TIME_DIFFERENCE_SECONDS = 90;

    public Plan plan(List<TripPattern> inputPatterns) {
        List<TripPattern> patterns = inputPatterns.stream()
                .sorted(Comparator.comparingInt(TripPattern::sourcePriority)
                        .thenComparing(pattern -> pattern.key().sourceId())
                        .thenComparing(pattern -> pattern.key().tripId()))
                .toList();
        Map<TripKey, TripKey> canonicalByTrip = new LinkedHashMap<>();
        Map<TripKey, List<StopCall>> fusedCalls = new LinkedHashMap<>();
        Set<TripKey> suppressed = new LinkedHashSet<>();
        List<Decision> decisions = new ArrayList<>();
        patterns.forEach(pattern -> canonicalByTrip.put(pattern.key(), pattern.key()));

        Map<String, List<TripPattern>> exactGroups = new LinkedHashMap<>();
        for (TripPattern pattern : patterns) {
            exactGroups.computeIfAbsent(pattern.exactKey(), ignored -> new ArrayList<>()).add(pattern);
        }
        for (List<TripPattern> group : exactGroups.values()) {
            if (group.stream().map(pattern -> pattern.key().sourceId()).distinct().count() < 2) {
                continue;
            }
            TripPattern primary = group.get(0);
            for (int index = 1; index < group.size(); index++) {
                TripPattern secondary = group.get(index);
                if (primary.key().sourceId().equals(secondary.key().sourceId())) {
                    continue;
                }
                suppress(
                        DecisionKind.EXACT_DUPLICATE,
                        primary,
                        secondary,
                        primary.calls(),
                        "same service, mode, line, stop sequence and times",
                        suppressed,
                        canonicalByTrip,
                        fusedCalls,
                        decisions
                );
            }
        }

        List<Pair> pairs = candidatePairs(patterns, suppressed);
        pairs.sort(Comparator.comparingInt(Pair::largestStopCount).reversed()
                .thenComparing(pair -> pair.left().key().namespacedId())
                .thenComparing(pair -> pair.right().key().namespacedId()));

        for (Pair pair : pairs) {
            if (suppressed.contains(pair.left().key()) || suppressed.contains(pair.right().key())) {
                continue;
            }
            Containment containment = containment(pair.left(), pair.right());
            if (containment != null) {
                suppress(
                        DecisionKind.SUBSET_SUPPRESSED,
                        containment.longer(),
                        containment.shorter(),
                        containment.longer().calls(),
                        "shorter trip is a contiguous timed subset of the longer trip",
                        suppressed,
                        canonicalByTrip,
                        fusedCalls,
                        decisions
                );
            }
        }

        for (Pair pair : pairs) {
            if (suppressed.contains(pair.left().key()) || suppressed.contains(pair.right().key())) {
                continue;
            }
            Stitch stitch = stitch(pair.left(), pair.right());
            if (stitch != null) {
                TripPattern primary = preferred(pair.left(), pair.right());
                TripPattern secondary = primary == pair.left() ? pair.right() : pair.left();
                suppress(
                        DecisionKind.STITCHED,
                        primary,
                        secondary,
                        stitch.calls(),
                        "matching public journey identity with " + stitch.overlapCount()
                                + " shared timed boundary stops",
                        suppressed,
                        canonicalByTrip,
                        fusedCalls,
                        decisions
                );
            }
        }

        for (Pair pair : pairs) {
            if (suppressed.contains(pair.left().key()) || suppressed.contains(pair.right().key())) {
                continue;
            }
            int sharedCalls = sharedTimedCalls(pair.left().calls(), pair.right().calls());
            if (sharedCalls >= 2) {
                decisions.add(new Decision(
                        DecisionKind.AMBIGUOUS_KEPT,
                        preferred(pair.left(), pair.right()).key(),
                        preferred(pair.left(), pair.right()) == pair.left()
                                ? pair.right().key()
                                : pair.left().key(),
                        List.of(),
                        "shared " + sharedCalls + " timed stops but no safe containment or boundary stitch"
                ));
            }
        }

        return new Plan(decisions, canonicalByTrip, fusedCalls);
    }

    private static List<Pair> candidatePairs(List<TripPattern> patterns, Set<TripKey> suppressed) {
        Map<String, List<TripPattern>> buckets = new LinkedHashMap<>();
        for (TripPattern pattern : patterns) {
            if (!suppressed.contains(pattern.key())) {
                buckets.computeIfAbsent(pattern.comparisonBucket(), ignored -> new ArrayList<>()).add(pattern);
            }
        }
        List<Pair> pairs = new ArrayList<>();
        for (List<TripPattern> bucket : buckets.values()) {
            for (int leftIndex = 0; leftIndex < bucket.size(); leftIndex++) {
                TripPattern left = bucket.get(leftIndex);
                for (int rightIndex = leftIndex + 1; rightIndex < bucket.size(); rightIndex++) {
                    TripPattern right = bucket.get(rightIndex);
                    if (!left.key().sourceId().equals(right.key().sourceId())
                            && shareAtLeastTwoStopKeys(left.calls(), right.calls())) {
                        pairs.add(new Pair(left, right));
                    }
                }
            }
        }
        return pairs;
    }

    private static boolean shareAtLeastTwoStopKeys(List<StopCall> left, List<StopCall> right) {
        Set<String> keys = new LinkedHashSet<>();
        left.forEach(call -> keys.add(call.canonicalStopKey()));
        int shared = 0;
        Set<String> counted = new LinkedHashSet<>();
        for (StopCall call : right) {
            if (keys.contains(call.canonicalStopKey()) && counted.add(call.canonicalStopKey())) {
                shared++;
                if (shared >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Containment containment(TripPattern left, TripPattern right) {
        TripPattern longer = left.calls().size() >= right.calls().size() ? left : right;
        TripPattern shorter = longer == left ? right : left;
        if (longer.calls().size() == shorter.calls().size()) {
            return null;
        }
        int offset = contiguousOffset(longer.calls(), shorter.calls());
        return offset >= 0 ? new Containment(longer, shorter) : null;
    }

    private static int contiguousOffset(List<StopCall> longer, List<StopCall> shorter) {
        for (int offset = 0; offset <= longer.size() - shorter.size(); offset++) {
            boolean matches = true;
            for (int index = 0; index < shorter.size(); index++) {
                if (!sameTimedCall(longer.get(offset + index), shorter.get(index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return offset;
            }
        }
        return -1;
    }

    private static Stitch stitch(TripPattern left, TripPattern right) {
        if (left.journeyKey().isBlank() || !left.journeyKey().equals(right.journeyKey())) {
            return null;
        }
        Stitch forward = stitchInOrder(left.calls(), right.calls());
        Stitch reverse = stitchInOrder(right.calls(), left.calls());
        if (forward == null) {
            return reverse;
        }
        if (reverse == null || forward.calls().size() >= reverse.calls().size()) {
            return forward;
        }
        return reverse;
    }

    private static Stitch stitchInOrder(List<StopCall> first, List<StopCall> second) {
        int maximumOverlap = Math.min(first.size(), second.size());
        for (int overlap = maximumOverlap; overlap >= 2; overlap--) {
            boolean matches = true;
            for (int index = 0; index < overlap; index++) {
                if (!sameTimedCall(first.get(first.size() - overlap + index), second.get(index))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                List<StopCall> merged = new ArrayList<>(first);
                merged.addAll(second.subList(overlap, second.size()));
                return new Stitch(List.copyOf(merged), overlap);
            }
        }
        return null;
    }

    private static int sharedTimedCalls(List<StopCall> left, List<StopCall> right) {
        int shared = 0;
        Set<Integer> matchedRight = new LinkedHashSet<>();
        for (StopCall leftCall : left) {
            for (int index = 0; index < right.size(); index++) {
                if (!matchedRight.contains(index) && sameTimedCall(leftCall, right.get(index))) {
                    matchedRight.add(index);
                    shared++;
                    break;
                }
            }
        }
        return shared;
    }

    private static boolean sameTimedCall(StopCall left, StopCall right) {
        if (!left.canonicalStopKey().equals(right.canonicalStopKey())) {
            return false;
        }
        return timesMatch(left.arrivalSeconds(), right.arrivalSeconds())
                && timesMatch(left.departureSeconds(), right.departureSeconds());
    }

    private static boolean timesMatch(int left, int right) {
        return left < 0 || right < 0 || Math.abs(left - right) <= MAX_TIME_DIFFERENCE_SECONDS;
    }

    private static TripPattern preferred(TripPattern left, TripPattern right) {
        Comparator<TripPattern> comparator = Comparator.comparingInt(TripPattern::sourcePriority)
                .thenComparing(pattern -> pattern.key().sourceId())
                .thenComparing(pattern -> pattern.key().tripId());
        return comparator.compare(left, right) <= 0 ? left : right;
    }

    private static void suppress(
            DecisionKind kind,
            TripPattern primary,
            TripPattern secondary,
            List<StopCall> canonicalCalls,
            String reason,
            Set<TripKey> suppressed,
            Map<TripKey, TripKey> canonicalByTrip,
            Map<TripKey, List<StopCall>> fusedCalls,
            List<Decision> decisions
    ) {
        suppressed.add(secondary.key());
        canonicalByTrip.put(secondary.key(), primary.key());
        if (kind == DecisionKind.STITCHED) {
            fusedCalls.put(primary.key(), List.copyOf(canonicalCalls));
        }
        decisions.add(new Decision(
                kind,
                primary.key(),
                secondary.key(),
                kind == DecisionKind.STITCHED ? List.copyOf(canonicalCalls) : List.of(),
                reason
        ));
    }

    public enum DecisionKind {
        EXACT_DUPLICATE,
        SUBSET_SUPPRESSED,
        STITCHED,
        AMBIGUOUS_KEPT
    }

    public record TripKey(String sourceId, String tripId) {
        public TripKey {
            requireText(sourceId, "sourceId");
            requireText(tripId, "tripId");
        }

        public String namespacedId() {
            return sourceId + "::" + tripId;
        }
    }

    public record StopCall(
            String sourceId,
            String stopId,
            String canonicalStopKey,
            int arrivalSeconds,
            int departureSeconds
    ) {
        public StopCall {
            requireText(sourceId, "sourceId");
            requireText(stopId, "stopId");
            requireText(canonicalStopKey, "canonicalStopKey");
        }
    }

    public record TripPattern(
            TripKey key,
            int sourcePriority,
            int routeType,
            String lineKey,
            String journeyKey,
            String serviceSignature,
            List<StopCall> calls
    ) {
        public TripPattern {
            Objects.requireNonNull(key, "key");
            lineKey = lineKey == null ? "" : lineKey;
            journeyKey = journeyKey == null ? "" : journeyKey;
            requireText(serviceSignature, "serviceSignature");
            calls = List.copyOf(calls);
            if (calls.size() < 2) {
                throw new IllegalArgumentException("A fusion trip requires at least two stop calls: " + key);
            }
        }

        String comparisonBucket() {
            return serviceSignature + '\u001f' + routeType + '\u001f' + lineKey;
        }

        String exactKey() {
            StringBuilder keyBuilder = new StringBuilder(comparisonBucket());
            calls.forEach(call -> keyBuilder.append('\u001e')
                    .append(call.canonicalStopKey()).append(':')
                    .append(call.arrivalSeconds()).append(':')
                    .append(call.departureSeconds()));
            return keyBuilder.toString();
        }
    }

    public record Decision(
            DecisionKind kind,
            TripKey primary,
            TripKey secondary,
            List<StopCall> fusedCalls,
            String reason
    ) {
        public Decision {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(primary, "primary");
            Objects.requireNonNull(secondary, "secondary");
            fusedCalls = List.copyOf(fusedCalls);
            reason = reason == null ? "" : reason;
        }
    }

    public record Plan(
            List<Decision> decisions,
            Map<TripKey, TripKey> canonicalByTrip,
            Map<TripKey, List<StopCall>> fusedCallsByCanonicalTrip
    ) {
        public Plan {
            decisions = List.copyOf(decisions);
            canonicalByTrip = Map.copyOf(canonicalByTrip);
            Map<TripKey, List<StopCall>> copiedCalls = new HashMap<>();
            fusedCallsByCanonicalTrip.forEach((key, calls) -> copiedCalls.put(key, List.copyOf(calls)));
            fusedCallsByCanonicalTrip = Map.copyOf(copiedCalls);
        }

        public Map<DecisionKind, Long> counts() {
            Map<DecisionKind, Long> counts = new LinkedHashMap<>();
            for (DecisionKind kind : DecisionKind.values()) {
                counts.put(kind, decisions.stream().filter(decision -> decision.kind() == kind).count());
            }
            return Map.copyOf(counts);
        }
    }

    private record Pair(TripPattern left, TripPattern right) {
        int largestStopCount() {
            return Math.max(left.calls().size(), right.calls().size());
        }
    }

    private record Containment(TripPattern longer, TripPattern shorter) {
    }

    private record Stitch(List<StopCall> calls, int overlapCount) {
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
