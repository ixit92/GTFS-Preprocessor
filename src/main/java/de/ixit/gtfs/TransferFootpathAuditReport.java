package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record TransferFootpathAuditReport(
        String auditVersion,
        boolean available,
        boolean pass,
        long rawTransfers,
        long scopedTransfers,
        Map<String, Long> transferSemanticCounts,
        long transferEdges,
        long traversableTransferEdges,
        long nonPedestrianGtfsEdges,
        long scopedGtfsEdges,
        long traversableHeuristicEdges,
        long traversableAreaMembershipEdges,
        long stopFootpaths,
        long traversableStopFootpaths,
        long unknownStopFootpaths,
        long overDistanceTraversableFootpaths,
        long zeroTimeTraversableFootpaths,
        long multiStopAreas,
        long areasWithoutFootpathRows,
        long oversizedStopAreas,
        long extremeStopAreas,
        Integer maximumFootpathDistanceMeters,
        List<String> samples
) {
}
