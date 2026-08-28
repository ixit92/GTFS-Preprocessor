package de.ixit.gtfs;

import de.ixit.gtfs.model.CalendarRow;
import de.ixit.gtfs.model.CalendarDateRow;
import de.ixit.gtfs.model.Agency;
import de.ixit.gtfs.model.FeedInfo;
import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.Trip;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class GtfsParsers {
    private GtfsParsers() {
    }

    public static List<Agency> readAgencies(InputStream inputStream) throws IOException {
        List<Agency> agencies = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> agencies.add(new Agency(
                row.optional("agency_id"),
                row.required("agency_name"),
                row.optional("agency_timezone")
        )));
        return agencies;
    }

    public static List<Stop> readStops(InputStream inputStream) throws IOException {
        List<Stop> stops = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> stops.add(new Stop(
                row.required("stop_id"),
                row.optional("stop_code"),
                row.optional("stop_name"),
                row.optionalDouble("stop_lat"),
                row.optionalDouble("stop_lon"),
                row.optional("parent_station"),
                row.optionalInt("location_type"),
                row.optional("platform_code")
        )));
        return stops;
    }

    public static List<Route> readRoutes(InputStream inputStream) throws IOException {
        List<Route> routes = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> routes.add(new Route(
                row.required("route_id"),
                row.optional("agency_id"),
                row.optional("route_short_name"),
                row.optional("route_long_name"),
                row.optionalInt("route_type"),
                row.optional("route_color"),
                row.optional("route_text_color")
        )));
        return routes;
    }

    public static List<Trip> readTrips(InputStream inputStream) throws IOException {
        List<Trip> trips = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> trips.add(new Trip(
                row.required("trip_id"),
                row.required("route_id"),
                row.required("service_id"),
                row.optional("trip_headsign"),
                row.optional("direction_id"),
                row.optional("block_id"),
                row.optional("shape_id")
        )));
        return trips;
    }

    public static List<CalendarRow> readCalendar(InputStream inputStream) throws IOException {
        List<CalendarRow> calendarRows = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> calendarRows.add(new CalendarRow(
                row.required("service_id"),
                row.optionalInt("monday"),
                row.optionalInt("tuesday"),
                row.optionalInt("wednesday"),
                row.optionalInt("thursday"),
                row.optionalInt("friday"),
                row.optionalInt("saturday"),
                row.optionalInt("sunday"),
                row.optional("start_date"),
                row.optional("end_date")
        )));
        return calendarRows;
    }

    public static List<CalendarDateRow> readCalendarDates(InputStream inputStream) throws IOException {
        List<CalendarDateRow> calendarDateRows = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> calendarDateRows.add(new CalendarDateRow(
                row.required("service_id"),
                row.required("date"),
                row.optionalInt("exception_type")
        )));
        return calendarDateRows;
    }

    public static Optional<FeedInfo> readFeedInfo(InputStream inputStream) throws IOException {
        List<FeedInfo> feedInfos = new ArrayList<>();
        GtfsCsvReader.read(inputStream, row -> {
            if (feedInfos.isEmpty()) {
                feedInfos.add(new FeedInfo(
                        firstNonBlank(row.optional("feed_id"), row.optional("feed_publisher_name")),
                        row.optional("feed_version")
                ));
            }
        });
        return feedInfos.stream().findFirst();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }
}
