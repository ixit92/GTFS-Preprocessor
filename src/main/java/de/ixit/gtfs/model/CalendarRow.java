package de.ixit.gtfs.model;

public record CalendarRow(
        String serviceId,
        Integer monday,
        Integer tuesday,
        Integer wednesday,
        Integer thursday,
        Integer friday,
        Integer saturday,
        Integer sunday,
        String startDate,
        String endDate
) {
}
