package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.GtfsTransfer;
import de.ixit.gtfs.model.TransferRule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TransferRuleBuilder {
    private static final int VERY_LONG_TRANSFER_SECONDS = 3600;

    private final Map<String, String> areaIdByStopId = new HashMap<>();
    private final Map<String, Integer> stopCountByAreaId = new HashMap<>();
    private final List<TransferRule> gtfsTransferRules = new ArrayList<>();
    private final Set<String> unmappedTransferSamples = new LinkedHashSet<>();
    private final List<String> suspiciousSamples = new ArrayList<>();
    private int gtfsTransfersObserved;
    private int gtfsTransfersMapped;
    private int gtfsTransfersUnmapped;
    private int scopedGtfsTransfers;
    private int pedestrianCandidateTransfers;
    private int excludedNonPedestrianTransfers;

    public TransferRuleBuilder(List<Stop> stops) {
        for (Stop stop : stops) {
            String areaId = StopAreaBuilder.areaIdFor(stop);
            areaIdByStopId.put(stop.stopId(), areaId);
            stopCountByAreaId.merge(areaId, 1, Integer::sum);
        }
    }

    public void observeGtfsTransfer(GtfsTransfer transfer) {
        gtfsTransfersObserved++;
        String fromStopId = transfer.fromStopId();
        String toStopId = transfer.toStopId();
        String fromAreaId = areaIdByStopId.get(fromStopId);
        String toAreaId = areaIdByStopId.get(toStopId);
        if (fromAreaId == null || toAreaId == null) {
            gtfsTransfersUnmapped++;
            if (unmappedTransferSamples.size() < 5) {
                unmappedTransferSamples.add(fromStopId + "->" + toStopId);
            }
            return;
        }

        gtfsTransfersMapped++;
        if (!"STOP".equals(transfer.scopeType())) {
            scopedGtfsTransfers++;
        }
        if (transfer.isUnscopedPedestrianTransfer()) {
            pedestrianCandidateTransfers++;
        } else {
            excludedNonPedestrianTransfers++;
        }
        inspectSuspiciousTransfer(fromStopId, toStopId, transfer.minTransferTimeSeconds());
        String ruleId = "gtfs_transfer_" + transfer.transferId();
        gtfsTransferRules.add(new TransferRule(
                ruleId,
                transfer.transferId(),
                fromAreaId,
                toAreaId,
                fromStopId,
                toStopId,
                transfer.transferType(),
                transfer.minTransferTimeSeconds(),
                transfer.semantic(),
                transfer.scopeType(),
                transfer.isUnscopedPedestrianTransfer(),
                "GTFS_TRANSFERS",
                "HIGH",
                "Mapped from GTFS transfers.txt; full route/trip/service scope remains in transfers via raw_transfer_id."
        ));
    }

    public TransferRuleBuildResult build() {
        List<TransferRule> rules = gtfsTransferRules;
        int sameStopAreaRules = 0;
        for (Map.Entry<String, Integer> entry : stopCountByAreaId.entrySet()) {
            if (entry.getValue() <= 1) {
                continue;
            }
            String areaId = entry.getKey();
            rules.add(new TransferRule(
                    "same_area_" + sanitize(areaId),
                    null,
                    areaId,
                    areaId,
                    null,
                    null,
                    null,
                    null,
                    "AREA_MEMBERSHIP",
                    "AREA",
                    false,
                    "SAME_STOP_AREA",
                    "LOW",
                    "Area membership candidate only; concrete walking evidence is stored separately in stop_footpaths."
            ));
            sameStopAreaRules++;
        }

        rules.sort(Comparator.comparing(TransferRule::transferRuleId));
        TransferRuleStats stats = TransferRuleStats.from(
                rules,
                gtfsTransfersObserved,
                gtfsTransfersMapped,
                gtfsTransfersUnmapped,
                sameStopAreaRules,
                scopedGtfsTransfers,
                pedestrianCandidateTransfers,
                excludedNonPedestrianTransfers,
                unmappedTransferSamples,
                suspiciousSamples
        );
        areaIdByStopId.clear();
        stopCountByAreaId.clear();
        return new TransferRuleBuildResult(Collections.unmodifiableList(rules), stats);
    }

    private void inspectSuspiciousTransfer(String fromStopId, String toStopId, Integer minTransferTimeSeconds) {
        if (minTransferTimeSeconds == null) {
            return;
        }
        if (minTransferTimeSeconds < 0 && suspiciousSamples.size() < 5) {
            suspiciousSamples.add("negative_time:" + fromStopId + "->" + toStopId + "=" + minTransferTimeSeconds);
        } else if (minTransferTimeSeconds > VERY_LONG_TRANSFER_SECONDS && suspiciousSamples.size() < 5) {
            suspiciousSamples.add("very_long_time:" + fromStopId + "->" + toStopId + "=" + minTransferTimeSeconds);
        }
    }

    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[^A-Za-z0-9]+", "_");
    }

    public record TransferRuleBuildResult(List<TransferRule> rules, TransferRuleStats stats) {
    }

    public record TransferRuleStats(
            int ruleCount,
            Map<String, Integer> sourceCounts,
            Map<String, Integer> confidenceCounts,
            int gtfsTransfersObserved,
            int gtfsTransfersMapped,
            int gtfsTransfersUnmapped,
            int sameStopAreaRules,
            int scopedGtfsTransfers,
            int pedestrianCandidateTransfers,
            int excludedNonPedestrianTransfers,
            Map<String, Integer> semanticCounts,
            List<String> unmappedTransferSamples,
            List<String> suspiciousSamples
    ) {
        public static TransferRuleStats from(
                List<TransferRule> rules,
                int gtfsTransfersObserved,
                int gtfsTransfersMapped,
                int gtfsTransfersUnmapped,
                int sameStopAreaRules,
                int scopedGtfsTransfers,
                int pedestrianCandidateTransfers,
                int excludedNonPedestrianTransfers,
                Set<String> unmappedTransferSamples,
                List<String> suspiciousSamples
        ) {
            Map<String, Integer> sourceCounts = new LinkedHashMap<>();
            sourceCounts.put("GTFS_TRANSFERS", 0);
            sourceCounts.put("SAME_STOP_AREA", 0);
            sourceCounts.put("GENERATED_PLATFORM_TRANSFER", 0);
            sourceCounts.put("GENERATED_NEARBY_AREA", 0);
            Map<String, Integer> confidenceCounts = new LinkedHashMap<>();
            confidenceCounts.put("HIGH", 0);
            confidenceCounts.put("MEDIUM", 0);
            confidenceCounts.put("LOW", 0);
            Map<String, Integer> semanticCounts = new LinkedHashMap<>();

            for (TransferRule rule : rules) {
                sourceCounts.merge(rule.source(), 1, Integer::sum);
                confidenceCounts.merge(rule.confidence(), 1, Integer::sum);
                semanticCounts.merge(rule.transferSemantic(), 1, Integer::sum);
            }

            return new TransferRuleStats(
                    rules.size(),
                    new LinkedHashMap<>(sourceCounts),
                    new LinkedHashMap<>(confidenceCounts),
                    gtfsTransfersObserved,
                    gtfsTransfersMapped,
                    gtfsTransfersUnmapped,
                    sameStopAreaRules,
                    scopedGtfsTransfers,
                    pedestrianCandidateTransfers,
                    excludedNonPedestrianTransfers,
                    new LinkedHashMap<>(semanticCounts),
                    List.copyOf(unmappedTransferSamples),
                    List.copyOf(suspiciousSamples)
            );
        }
    }
}
