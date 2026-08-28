package de.ixit.gtfs;

public final class GtfsTimeParser {
    private GtfsTimeParser() {
    }

    public static int toSecondsSinceServiceDayStart(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing GTFS time value.");
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid GTFS time: " + value);
        }

        int hours = parseNonNegative(parts[0], value);
        int minutes = parseRange(parts[1], 0, 59, value);
        int seconds = parseRange(parts[2], 0, 59, value);
        return hours * 3600 + minutes * 60 + seconds;
    }

    private static int parseNonNegative(String part, String original) {
        int parsed = Integer.parseInt(part);
        if (parsed < 0) {
            throw new IllegalArgumentException("Invalid GTFS time: " + original);
        }
        return parsed;
    }

    private static int parseRange(String part, int min, int max, String original) {
        int parsed = Integer.parseInt(part);
        if (parsed < min || parsed > max) {
            throw new IllegalArgumentException("Invalid GTFS time: " + original);
        }
        return parsed;
    }
}
