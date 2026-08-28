package de.ixit.gtfs;

import java.util.List;
import java.util.Map;

public record ServiceDayModelReport(
        String modelVersion,
        boolean available,
        long serviceCount,
        long tripServiceCount,
        long baseCalendarServiceCount,
        long exceptionServiceCount,
        long exceptionOnlyServiceCount,
        long unresolvedTripServiceCount,
        long invalidWeekdayFlagCount,
        long invalidCalendarRangeCount,
        long invalidExceptionDateCount,
        long invalidExceptionTypeCount,
        long invalidIanaTimezoneServiceCount,
        long unknownTimezoneTripServiceCount,
        long multipleTimezoneTripServiceCount,
        long overflowStopTimeCount,
        long maximumServiceDaySeconds,
        Map<String, Long> timezoneCounts,
        List<String> samples
) {
    public boolean pass() {
        return available
                && unresolvedTripServiceCount == 0
                && invalidWeekdayFlagCount == 0
                && invalidCalendarRangeCount == 0
                && invalidExceptionDateCount == 0
                && invalidExceptionTypeCount == 0
                && invalidIanaTimezoneServiceCount == 0
                && unknownTimezoneTripServiceCount == 0
                && multipleTimezoneTripServiceCount == 0;
    }
}
