package de.ixit.gtfs;

import java.util.Map;

public record PerformanceReport(
        long totalMs,
        long usedMemoryEstimateMb,
        Map<String, Long> sectionMs,
        Map<String, Long> memorySnapshotsMb
) {
}
