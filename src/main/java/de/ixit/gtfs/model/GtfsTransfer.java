package de.ixit.gtfs.model;

public record GtfsTransfer(
        long transferId,
        String fromStopId,
        String toStopId,
        String fromRouteId,
        String toRouteId,
        String fromTripId,
        String toTripId,
        Integer transferType,
        Integer minTransferTimeSeconds,
        String serviceId
) {
    public String semantic() {
        if (transferType == null || transferType == 0) {
            return "RECOMMENDED";
        }
        return switch (transferType) {
            case 1 -> "TIMED";
            case 2 -> "MINIMUM_TIME";
            case 3 -> "PROHIBITED";
            case 4 -> "IN_SEAT_ALLOWED";
            case 5 -> "IN_SEAT_FORBIDDEN";
            default -> "UNKNOWN";
        };
    }

    public String scopeType() {
        if (hasText(fromTripId) || hasText(toTripId)) {
            return hasText(serviceId) ? "TRIP_SERVICE" : "TRIP";
        }
        if (hasText(fromRouteId) || hasText(toRouteId)) {
            return "ROUTE";
        }
        if (hasText(serviceId)) {
            return "SERVICE";
        }
        return "STOP";
    }

    public boolean isUnscopedPedestrianTransfer() {
        return "STOP".equals(scopeType())
                && (transferType == null || transferType == 0 || transferType == 2);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
