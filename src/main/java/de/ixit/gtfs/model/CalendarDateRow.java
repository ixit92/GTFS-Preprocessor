package de.ixit.gtfs.model;

public record CalendarDateRow(
        String serviceId,
        String date,
        Integer exceptionType
) {
}
