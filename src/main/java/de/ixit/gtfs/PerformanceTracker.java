package de.ixit.gtfs;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PerformanceTracker {
    private final long startedNanos = System.nanoTime();
    private final Map<String, Long> sectionMs = new LinkedHashMap<>();
    private final Map<String, Long> memorySnapshotsMb = new LinkedHashMap<>();

    public <T> T measure(String name, ThrowingSupplier<T> supplier) throws Exception {
        long start = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            record(name, start);
        }
    }

    public void measure(String name, ThrowingRunnable runnable) throws Exception {
        long start = System.nanoTime();
        try {
            runnable.run();
        } finally {
            record(name, start);
        }
    }

    public PerformanceReport snapshot() {
        long totalMs = elapsedMs(startedNanos);
        return new PerformanceReport(totalMs, usedMemoryMb(), new LinkedHashMap<>(sectionMs), new LinkedHashMap<>(memorySnapshotsMb));
    }

    public void snapshotMemory(String name) {
        memorySnapshotsMb.put(name, usedMemoryMb());
    }

    private void record(String name, long startNanos) {
        sectionMs.merge(name, elapsedMs(startNanos), Long::sum);
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    static long usedMemoryMb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }
}
