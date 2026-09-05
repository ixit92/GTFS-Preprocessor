package de.ixit.gtfs;

/** Preparation defaults for a typical pedestrian, not an accessibility guarantee. */
public final class WalkTimeModel {
    public static final int BUFFER_SECONDS = 60;
    public static final int MINIMUM_TRANSFER_SECONDS = 120;
    public static final double SPEED_METERS_PER_SECOND = 1.2;
    public static final double DETOUR_FACTOR = 1.35;

    private WalkTimeModel() {
    }

    public static int estimatedWalkSeconds(int straightLineMeters) {
        if (straightLineMeters < 0) throw new IllegalArgumentException("Negative walking distance");
        return boundedSeconds(Math.ceil(straightLineMeters * DETOUR_FACTOR / SPEED_METERS_PER_SECOND));
    }

    public static int minimumTransferSeconds(int walkSeconds, Integer gtfsMinimum) {
        if (walkSeconds < 0) throw new IllegalArgumentException("Negative walking time");
        return (int) Math.min(Integer.MAX_VALUE, Math.max(
                Math.max((long) walkSeconds + BUFFER_SECONDS, MINIMUM_TRANSFER_SECONDS),
                gtfsMinimum == null ? 0 : gtfsMinimum));
    }

    static int boundedSeconds(double seconds) {
        return (int) Math.min(Integer.MAX_VALUE, seconds);
    }
}
