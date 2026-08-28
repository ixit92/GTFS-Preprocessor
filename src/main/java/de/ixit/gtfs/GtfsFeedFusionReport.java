package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record GtfsFeedFusionReport(
        String generatedAt,
        String outputZip,
        List<SourceSummary> sources,
        long inputTrips,
        long outputTrips,
        long exactDuplicates,
        long subsetSuppressed,
        long stitched,
        long ambiguousKept,
        long matchedStopIdentities,
        boolean diagnosticsTruncated,
        List<Diagnostic> diagnostics
) {
    public GtfsFeedFusionReport {
        generatedAt = generatedAt == null || generatedAt.isBlank() ? Instant.now().toString() : generatedAt;
        sources = List.copyOf(sources);
        diagnostics = List.copyOf(diagnostics);
    }

    public void writeJson(Path output) throws IOException {
        Path parent = output.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(output.toFile(), this);
    }

    public String toConsoleText() {
        return "GTFS fusion complete\n"
                + "  sources: " + sources.size() + "\n"
                + "  trips: " + inputTrips + " -> " + outputTrips + "\n"
                + "  EXACT_DUPLICATE: " + exactDuplicates + "\n"
                + "  SUBSET_SUPPRESSED: " + subsetSuppressed + "\n"
                + "  STITCHED: " + stitched + "\n"
                + "  AMBIGUOUS_KEPT: " + ambiguousKept + "\n"
                + "  matched stop identities: " + matchedStopIdentities + "\n"
                + "  output: " + outputZip;
    }

    public static GtfsFeedFusionReport from(
            Path outputZip,
            List<SourceSummary> sources,
            long inputTrips,
            long outputTrips,
            long matchedStopIdentities,
            List<GtfsTripFusionPlanner.Decision> decisions,
            List<Diagnostic> diagnostics,
            boolean diagnosticsTruncated
    ) {
        Map<GtfsTripFusionPlanner.DecisionKind, Long> counts = decisions.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        GtfsTripFusionPlanner.Decision::kind,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ));
        return new GtfsFeedFusionReport(
                Instant.now().toString(),
                outputZip.toAbsolutePath().normalize().toString(),
                sources,
                inputTrips,
                outputTrips,
                counts.getOrDefault(GtfsTripFusionPlanner.DecisionKind.EXACT_DUPLICATE, 0L),
                counts.getOrDefault(GtfsTripFusionPlanner.DecisionKind.SUBSET_SUPPRESSED, 0L),
                counts.getOrDefault(GtfsTripFusionPlanner.DecisionKind.STITCHED, 0L),
                counts.getOrDefault(GtfsTripFusionPlanner.DecisionKind.AMBIGUOUS_KEPT, 0L),
                matchedStopIdentities,
                diagnosticsTruncated,
                diagnostics
        );
    }

    public record SourceSummary(
            String sourceId,
            int priority,
            String inputZip,
            long stops,
            long routes,
            long trips,
            long stopTimes
    ) {
    }

    public record Diagnostic(
            GtfsTripFusionPlanner.DecisionKind kind,
            String canonicalTripId,
            String secondaryTripId,
            String reason
    ) {
    }
}
