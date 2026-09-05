package de.ixit.gtfs;

import de.ixit.gtfs.model.CalendarRow;
import de.ixit.gtfs.model.CalendarDateRow;
import de.ixit.gtfs.model.Agency;
import de.ixit.gtfs.model.CanonicalStopArea;
import de.ixit.gtfs.model.CanonicalStopAreaMember;
import de.ixit.gtfs.model.CanonicalStopAreaTransferEdge;
import de.ixit.gtfs.model.DisplayNameQualityFinding;
import de.ixit.gtfs.model.AreaRouteServiceSummary;
import de.ixit.gtfs.model.FeedInfo;
import de.ixit.gtfs.model.GtfsTransfer;
import de.ixit.gtfs.model.HubProfile;
import de.ixit.gtfs.model.Route;
import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaAlias;
import de.ixit.gtfs.model.StopAreaCity;
import de.ixit.gtfs.model.StopAreaDisplayName;
import de.ixit.gtfs.model.StopAreaProfile;
import de.ixit.gtfs.model.StopSearchToken;
import de.ixit.gtfs.model.StopFootpath;
import de.ixit.gtfs.model.TransferEdge;
import de.ixit.gtfs.model.TransferRule;
import de.ixit.gtfs.model.Trip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class SqliteGtfsWriter implements AutoCloseable {
    private static final int BATCH_SIZE = Math.max(100, Integer.getInteger("ixit.gtfs.batchSize", 10_000));
    private static final int STOP_TIMES_COMMIT_ROWS = Math.max(BATCH_SIZE, Integer.getInteger("ixit.gtfs.stopTimesCommitRows", 250_000));
    private static final int CALENDAR_DATES_COMMIT_ROWS = Math.max(BATCH_SIZE, Integer.getInteger("ixit.gtfs.calendarDatesCommitRows", 100_000));
    private static final String SQLITE_JOURNAL_MODE = pragmaName("ixit.gtfs.sqlite.journalMode", "WAL");
    private static final String SQLITE_SYNCHRONOUS = pragmaName("ixit.gtfs.sqlite.synchronous", "NORMAL");
    private static final String SQLITE_TEMP_STORE = optionalPragmaName("ixit.gtfs.sqlite.tempStore");
    private static final String SQLITE_CACHE_SIZE = optionalPragmaInteger("ixit.gtfs.sqlite.cacheSize");
    private static final String SQLITE_LOCKING_MODE = optionalPragmaName("ixit.gtfs.sqlite.lockingMode");

    private final Connection connection;
    private final Path databasePath;
    private Map<String, String> activePragmas = Map.of();

    private SqliteGtfsWriter(Connection connection, Path databasePath) throws SQLException {
        this.connection = connection;
        this.databasePath = databasePath;
        configure();
        createSchema();
    }

    public static SqliteGtfsWriter create(Path databasePath) throws SQLException {
        return new SqliteGtfsWriter(DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath()), databasePath);
    }

    public void writeStops(List<Stop> stops) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stops(stop_id, stop_code, stop_name, stop_name_normalized, stop_lat, stop_lon, parent_station, location_type, platform_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (Stop stop : stops) {
                statement.setString(1, stop.stopId());
                statement.setString(2, stop.stopCode());
                statement.setString(3, stop.stopName());
                statement.setString(4, StopNameNormalizer.normalize(stop.stopName()));
                setDouble(statement, 5, stop.stopLat());
                setDouble(statement, 6, stop.stopLon());
                statement.setString(7, stop.parentStation());
                setInteger(statement, 8, stop.locationType());
                statement.setString(9, stop.platformCode());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeStopAreas(List<StopArea> stopAreas) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_areas(area_id, area_name, area_name_normalized, area_lat, area_lon, stop_count)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            for (StopArea area : stopAreas) {
                statement.setString(1, area.areaId());
                statement.setString(2, area.areaName());
                statement.setString(3, StopNameNormalizer.normalize(area.areaName()));
                setDouble(statement, 4, area.areaLat());
                setDouble(statement, 5, area.areaLon());
                statement.setInt(6, area.stopCount());
                statement.addBatch();
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeStopAreaCities(List<StopAreaCity> cities) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_area_cities(
                    area_id,
                    municipality_id,
                    city_name,
                    municipality_type,
                    source,
                    quality,
                    data_version,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (StopAreaCity city : cities) {
                statement.setString(1, city.areaId());
                statement.setString(2, city.municipalityId());
                statement.setString(3, city.cityName());
                statement.setString(4, city.municipalityType());
                statement.setString(5, city.source());
                statement.setString(6, city.quality());
                statement.setString(7, city.dataVersion());
                statement.setString(8, city.explanation());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeStopAreaMembers(List<Stop> stops) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_area_members(area_id, stop_id, member_role)
                VALUES (?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (Stop stop : stops) {
                statement.setString(1, StopAreaBuilder.areaIdFor(stop));
                statement.setString(2, stop.stopId());
                statement.setString(3, isParentStation(stop) ? "AREA_ANCHOR" : "STOP");
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeRoutes(List<Route> routes) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO routes(route_id, agency_id, route_short_name, route_long_name, route_type, route_color, route_text_color)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            for (Route route : routes) {
                statement.setString(1, route.routeId());
                statement.setString(2, route.agencyId());
                statement.setString(3, route.routeShortName());
                statement.setString(4, route.routeLongName());
                setInteger(statement, 5, route.routeType());
                statement.setString(6, route.routeColor());
                statement.setString(7, route.routeTextColor());
                statement.addBatch();
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeAgencies(List<Agency> agencies) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO feed_agencies(agency_key, agency_id, agency_name, agency_timezone)
                VALUES (?, ?, ?, ?)
                """)) {
            begin();
            for (int index = 0; index < agencies.size(); index++) {
                Agency agency = agencies.get(index);
                String agencyId = agency.agencyId() == null ? "" : agency.agencyId().trim();
                statement.setString(1, agencyId.isBlank() ? "__default__" + index : agencyId);
                statement.setString(2, agencyId);
                statement.setString(3, agency.agencyName());
                statement.setString(4, agency.agencyTimezone());
                statement.addBatch();
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeTrips(List<Trip> trips) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO trips(trip_id, route_id, service_id, trip_headsign, direction_id, block_id, shape_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (Trip trip : trips) {
                statement.setString(1, trip.tripId());
                statement.setString(2, trip.routeId());
                statement.setString(3, trip.serviceId());
                statement.setString(4, trip.tripHeadsign());
                statement.setString(5, trip.directionId());
                statement.setString(6, trip.blockId());
                statement.setString(7, trip.shapeId());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public StopTimesWriteReport writeStopTimes(InputStream inputStream, Set<String> knownStopIds, StopTimeObserver observer) throws IOException, SQLException {
        return writeStopTimes(inputStream, knownStopIds, observer, null);
    }

    public StopTimesWriteReport writeStopTimes(InputStream inputStream, Set<String> knownStopIds, StopTimeObserver observer, GtfsCsvReader.ProgressListener progressListener) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_times(
                    trip_id, arrival_seconds, departure_seconds, stop_id, stop_sequence,
                    pickup_type, drop_off_type, shape_dist_traveled
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            long startedNanos = System.nanoTime();
            begin();
            BatchCounter counter = new BatchCounter();
            RowCounter transactionRows = new RowCounter();
            CommitStats commitStats = new CommitStats();
            UnknownStopTracker unknownStopTracker = new UnknownStopTracker();
            long rows = GtfsCsvReader.read(inputStream, row -> {
                try {
                    String stopId = row.required("stop_id");
                    if (!knownStopIds.contains(stopId)) {
                        unknownStopTracker.add(stopId);
                    }
                    String tripId = row.required("trip_id");
                    int stopSequence = Integer.parseInt(row.required("stop_sequence"));
                    if (observer != null) {
                        observer.onStopTime(tripId, stopId, stopSequence);
                    }
                    statement.setString(1, tripId);
                    statement.setInt(2, GtfsTimeParser.toSecondsSinceServiceDayStart(row.required("arrival_time")));
                    statement.setInt(3, GtfsTimeParser.toSecondsSinceServiceDayStart(row.required("departure_time")));
                    statement.setString(4, stopId);
                    statement.setInt(5, stopSequence);
                    setInteger(statement, 6, row.optionalInt("pickup_type"));
                    setInteger(statement, 7, row.optionalInt("drop_off_type"));
                    setDouble(statement, 8, row.optionalDouble("shape_dist_traveled"));
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                    transactionRows.count++;
                    if (transactionRows.count >= STOP_TIMES_COMMIT_ROWS) {
                        commitStopTimesTransaction(statement, counter, commitStats);
                        begin();
                        transactionRows.count = 0;
                    }
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            }, progressListener);
            if (transactionRows.count > 0 || counter.count > 0) {
                commitStopTimesTransaction(statement, counter, commitStats);
            } else {
                commit();
            }
            long durationMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
            long rowsPerSecond = durationMs == 0 ? rows : rows * 1_000 / durationMs;
            return new StopTimesWriteReport(
                    rows,
                    unknownStopTracker.count,
                    List.copyOf(unknownStopTracker.samples),
                    durationMs,
                    rowsPerSecond,
                    BATCH_SIZE,
                    STOP_TIMES_COMMIT_ROWS,
                    commitStats.count,
                    commitStats.averageMs(),
                    commitStats.maxMs,
                    fileSize(databasePath),
                    fileSize(walPath()),
                    Map.copyOf(activePragmas)
            );
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    public long writeShapes(
            InputStream inputStream,
            GtfsCsvReader.ProgressListener progressListener
    ) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO shapes(
                    shape_id, shape_pt_sequence, shape_pt_lat, shape_pt_lon, shape_dist_traveled
                )
                VALUES (?, ?, ?, ?, ?)
                """)) {
            begin();
            BatchCounter counter = new BatchCounter();
            RowCounter transactionRows = new RowCounter();
            long rows = GtfsCsvReader.read(inputStream, row -> {
                try {
                    statement.setString(1, row.required("shape_id"));
                    statement.setInt(2, Integer.parseInt(row.required("shape_pt_sequence")));
                    statement.setDouble(3, Double.parseDouble(row.required("shape_pt_lat")));
                    statement.setDouble(4, Double.parseDouble(row.required("shape_pt_lon")));
                    setDouble(statement, 5, row.optionalDouble("shape_dist_traveled"));
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                    transactionRows.count++;
                    if (transactionRows.count >= STOP_TIMES_COMMIT_ROWS) {
                        statement.executeBatch();
                        counter.count = 0;
                        commit();
                        begin();
                        transactionRows.count = 0;
                    }
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            }, progressListener);
            statement.executeBatch();
            commit();
            return rows;
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    public long writeTransfers(InputStream inputStream, TransferObserver observer) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transfers(
                    transfer_id,
                    from_stop_id,
                    to_stop_id,
                    from_route_id,
                    to_route_id,
                    from_trip_id,
                    to_trip_id,
                    transfer_type,
                    min_transfer_time,
                    service_id
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            BatchCounter counter = new BatchCounter();
            RowCounter transferIds = new RowCounter();
            long rows = GtfsCsvReader.read(inputStream, row -> {
                try {
                    GtfsTransfer transfer = new GtfsTransfer(
                            ++transferIds.count,
                            row.required("from_stop_id"),
                            row.required("to_stop_id"),
                            row.optional("from_route_id"),
                            row.optional("to_route_id"),
                            row.optional("from_trip_id"),
                            row.optional("to_trip_id"),
                            row.optionalInt("transfer_type"),
                            row.optionalInt("min_transfer_time"),
                            row.optional("service_id")
                    );
                    if (observer != null) {
                        observer.onTransfer(transfer);
                    }
                    statement.setLong(1, transfer.transferId());
                    statement.setString(2, transfer.fromStopId());
                    statement.setString(3, transfer.toStopId());
                    statement.setString(4, transfer.fromRouteId());
                    statement.setString(5, transfer.toRouteId());
                    statement.setString(6, transfer.fromTripId());
                    statement.setString(7, transfer.toTripId());
                    setInteger(statement, 8, transfer.transferType());
                    setInteger(statement, 9, transfer.minTransferTimeSeconds());
                    statement.setString(10, transfer.serviceId());
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            });
            statement.executeBatch();
            commit();
            return rows;
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    public void writeCalendar(List<CalendarRow> calendarRows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO calendar(service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            for (CalendarRow row : calendarRows) {
                statement.setString(1, row.serviceId());
                setInteger(statement, 2, row.monday());
                setInteger(statement, 3, row.tuesday());
                setInteger(statement, 4, row.wednesday());
                setInteger(statement, 5, row.thursday());
                setInteger(statement, 6, row.friday());
                setInteger(statement, 7, row.saturday());
                setInteger(statement, 8, row.sunday());
                statement.setString(9, row.startDate());
                statement.setString(10, row.endDate());
                statement.addBatch();
            }
            statement.executeBatch();
            commit();
        }
    }

    public long writeCalendar(
            InputStream inputStream,
            GtfsCsvReader.ProgressListener progressListener
    ) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO calendar(service_id, monday, tuesday, wednesday, thursday, friday, saturday, sunday, start_date, end_date)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            BatchCounter counter = new BatchCounter();
            long rows = GtfsCsvReader.read(inputStream, row -> {
                try {
                    statement.setString(1, row.required("service_id"));
                    setInteger(statement, 2, row.optionalInt("monday"));
                    setInteger(statement, 3, row.optionalInt("tuesday"));
                    setInteger(statement, 4, row.optionalInt("wednesday"));
                    setInteger(statement, 5, row.optionalInt("thursday"));
                    setInteger(statement, 6, row.optionalInt("friday"));
                    setInteger(statement, 7, row.optionalInt("saturday"));
                    setInteger(statement, 8, row.optionalInt("sunday"));
                    statement.setString(9, row.optional("start_date"));
                    statement.setString(10, row.optional("end_date"));
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            }, progressListener);
            statement.executeBatch();
            commit();
            return rows;
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    public void writeCalendarDates(List<CalendarDateRow> calendarDateRows) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO calendar_dates(service_id, date, exception_type, exception_action, source)
                VALUES (?, ?, ?, ?, 'GTFS_CALENDAR_DATES')
                """)) {
            begin();
            for (CalendarDateRow row : calendarDateRows) {
                statement.setString(1, row.serviceId());
                statement.setString(2, row.date());
                setInteger(statement, 3, row.exceptionType());
                statement.setString(4, exceptionAction(row.exceptionType()));
                statement.addBatch();
            }
            statement.executeBatch();
            commit();
        }
    }

    public long writeCalendarDates(
            InputStream inputStream,
            GtfsCsvReader.ProgressListener progressListener
    ) throws IOException, SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO calendar_dates(service_id, date, exception_type, exception_action, source)
                VALUES (?, ?, ?, ?, 'GTFS_CALENDAR_DATES')
                """)) {
            begin();
            BatchCounter counter = new BatchCounter();
            RowCounter transactionRows = new RowCounter();
            long rows = GtfsCsvReader.read(inputStream, row -> {
                try {
                    statement.setString(1, row.required("service_id"));
                    statement.setString(2, row.required("date"));
                    Integer exceptionType = row.optionalInt("exception_type");
                    setInteger(statement, 3, exceptionType);
                    statement.setString(4, exceptionAction(exceptionType));
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                    transactionRows.count++;
                    if (transactionRows.count >= CALENDAR_DATES_COMMIT_ROWS) {
                        statement.executeBatch();
                        counter.count = 0;
                        commit();
                        begin();
                        transactionRows.count = 0;
                    }
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            }, progressListener);
            statement.executeBatch();
            commit();
            return rows;
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    public long buildServiceCalendarSummary() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            begin();
            statement.executeUpdate("DELETE FROM service_calendar_summary");
            statement.executeUpdate("""
                    WITH service_ids AS (
                        SELECT service_id FROM trips
                        UNION
                        SELECT service_id FROM calendar
                        UNION
                        SELECT service_id FROM calendar_dates
                    ),
                    trip_stats AS (
                        SELECT service_id, COUNT(*) AS trip_count
                        FROM trips
                        GROUP BY service_id
                    ),
                    exception_stats AS (
                        SELECT service_id,
                               SUM(CASE WHEN exception_type = 1 THEN 1 ELSE 0 END) AS addition_count,
                               SUM(CASE WHEN exception_type = 2 THEN 1 ELSE 0 END) AS removal_count,
                               MIN(date) AS first_exception_date,
                               MAX(date) AS last_exception_date
                        FROM calendar_dates
                        GROUP BY service_id
                    ),
                    service_timezones AS (
                        SELECT trips.service_id,
                               COUNT(DISTINCT NULLIF(TRIM(feed_agencies.agency_timezone), '')) AS timezone_count,
                               MIN(NULLIF(TRIM(feed_agencies.agency_timezone), '')) AS service_timezone
                        FROM trips
                        JOIN routes ON routes.route_id = trips.route_id
                        LEFT JOIN feed_agencies
                          ON COALESCE(feed_agencies.agency_id, '') = COALESCE(routes.agency_id, '')
                        GROUP BY trips.service_id
                    ),
                    global_timezone AS (
                        SELECT COUNT(DISTINCT NULLIF(TRIM(agency_timezone), '')) AS timezone_count,
                               MIN(NULLIF(TRIM(agency_timezone), '')) AS service_timezone
                        FROM feed_agencies
                    )
                    INSERT INTO service_calendar_summary(
                        service_id, has_calendar, weekday_mask, start_date, end_date,
                        addition_count, removal_count, first_exception_date, last_exception_date,
                        trip_count, service_timezone, status, explanation
                    )
                    SELECT ids.service_id,
                           CASE WHEN calendar.service_id IS NULL THEN 0 ELSE 1 END,
                           COALESCE(calendar.monday, 0)
                             + COALESCE(calendar.tuesday, 0) * 2
                             + COALESCE(calendar.wednesday, 0) * 4
                             + COALESCE(calendar.thursday, 0) * 8
                             + COALESCE(calendar.friday, 0) * 16
                             + COALESCE(calendar.saturday, 0) * 32
                             + COALESCE(calendar.sunday, 0) * 64,
                           calendar.start_date,
                           calendar.end_date,
                           COALESCE(exceptions.addition_count, 0),
                           COALESCE(exceptions.removal_count, 0),
                           exceptions.first_exception_date,
                           exceptions.last_exception_date,
                           COALESCE(trip_stats.trip_count, 0),
                           CASE
                               WHEN service_timezones.timezone_count = 1 THEN service_timezones.service_timezone
                               WHEN service_timezones.timezone_count > 1 THEN 'MULTIPLE'
                               WHEN global_timezone.timezone_count = 1 THEN global_timezone.service_timezone
                               ELSE 'UNKNOWN'
                           END,
                           CASE
                               WHEN calendar.service_id IS NOT NULL
                                    AND COALESCE(exceptions.addition_count, 0) + COALESCE(exceptions.removal_count, 0) > 0
                                    THEN 'BASE_WITH_EXCEPTIONS'
                               WHEN calendar.service_id IS NOT NULL THEN 'BASE_ONLY'
                               WHEN COALESCE(exceptions.addition_count, 0) + COALESCE(exceptions.removal_count, 0) > 0
                                    THEN 'EXCEPTIONS_ONLY'
                               ELSE 'UNRESOLVED'
                           END,
                           'calendar_dates overrides calendar; service date is interpreted in the recorded agency timezone'
                    FROM service_ids ids
                    LEFT JOIN calendar ON calendar.service_id = ids.service_id
                    LEFT JOIN trip_stats ON trip_stats.service_id = ids.service_id
                    LEFT JOIN exception_stats exceptions ON exceptions.service_id = ids.service_id
                    LEFT JOIN service_timezones ON service_timezones.service_id = ids.service_id
                    CROSS JOIN global_timezone
                    """);
            commit();
        }
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM service_calendar_summary")) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }

    public StopSearchTokenBuilder.StreamingStats writeStopSearchTokens(
            GtfsCsvReader.ProgressListener progress) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_search_tokens(stop_id, area_id, token, token_type, source)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            begin();
            int[] batched = new int[1];
            var stats = StopSearchTokenBuilder.streamFromDatabase(databasePath, token -> {
                statement.setString(1, token.stopId());
                statement.setString(2, token.areaId());
                statement.setString(3, token.token());
                statement.setString(4, token.tokenType());
                statement.setString(5, token.source());
                statement.addBatch();
                batched[0] = executeBatchIfNeeded(statement, batched[0]);
            }, progress);
            statement.executeBatch();
            commit();
            return stats;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    public void writeStopSearchTokens(List<StopSearchToken> tokens) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_search_tokens(stop_id, area_id, token, token_type, source)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (StopSearchToken token : tokens) {
                statement.setString(1, token.stopId());
                statement.setString(2, token.areaId());
                statement.setString(3, token.token());
                statement.setString(4, token.tokenType());
                statement.setString(5, token.source());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeStopAreaAliases(List<StopAreaAlias> aliases) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_area_aliases(area_id, alias, alias_normalized, alias_type, source, priority)
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (StopAreaAlias alias : aliases) {
                statement.setString(1, alias.areaId());
                statement.setString(2, alias.alias());
                statement.setString(3, alias.aliasNormalized());
                statement.setString(4, alias.aliasType());
                statement.setString(5, alias.source());
                statement.setInt(6, alias.priority());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeHubProfiles(List<HubProfile> profiles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO hub_profiles(
                    area_id,
                    hub_level,
                    stop_count,
                    route_count,
                    trip_count,
                    route_type_count,
                    stop_time_count,
                    has_train,
                    has_subway,
                    has_tram,
                    has_bus,
                    has_rail_keyword,
                    has_main_station_keyword,
                    transfer_candidate_score,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (HubProfile profile : profiles) {
                statement.setString(1, profile.areaId());
                statement.setString(2, profile.hubLevel());
                statement.setInt(3, profile.stopCount());
                statement.setInt(4, profile.routeCount());
                statement.setInt(5, profile.tripCount());
                statement.setInt(6, profile.routeTypeCount());
                statement.setInt(7, profile.stopTimeCount());
                statement.setInt(8, bool(profile.hasTrain()));
                statement.setInt(9, bool(profile.hasSubway()));
                statement.setInt(10, bool(profile.hasTram()));
                statement.setInt(11, bool(profile.hasBus()));
                statement.setInt(12, bool(profile.hasRailKeyword()));
                statement.setInt(13, bool(profile.hasMainStationKeyword()));
                statement.setInt(14, profile.transferCandidateScore());
                statement.setString(15, profile.explanation());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeDisplayNameQualityFindings(List<DisplayNameQualityFinding> findings) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO display_name_quality_findings(
                    area_id,
                    finding_type,
                    classification,
                    prefix,
                    public_stop_name,
                    public_city_name,
                    public_display_name,
                    action,
                    rationale
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (DisplayNameQualityFinding finding : findings) {
                statement.setString(1, finding.areaId());
                statement.setString(2, finding.findingType());
                statement.setString(3, finding.classification());
                statement.setString(4, finding.prefix());
                statement.setString(5, finding.publicStopName());
                statement.setString(6, finding.publicCityName());
                statement.setString(7, finding.publicDisplayName());
                statement.setString(8, finding.action());
                statement.setString(9, finding.rationale());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeStopAreaProfiles(List<StopAreaProfile> profiles) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_area_profiles(
                    area_id,
                    profile_class,
                    stop_count,
                    platform_count,
                    route_count,
                    trip_count,
                    stop_time_count,
                    route_types,
                    line_labels,
                    has_rail_service,
                    has_train,
                    has_subway,
                    has_tram,
                    has_bus,
                    bus_only,
                    station_name_signal,
                    main_station_signal,
                    search_priority_score,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (StopAreaProfile profile : profiles) {
                statement.setString(1, profile.areaId());
                statement.setString(2, profile.profileClass());
                statement.setInt(3, profile.stopCount());
                statement.setInt(4, profile.platformCount());
                statement.setInt(5, profile.routeCount());
                statement.setInt(6, profile.tripCount());
                statement.setInt(7, profile.stopTimeCount());
                statement.setString(8, profile.routeTypes());
                statement.setString(9, profile.lineLabels());
                statement.setInt(10, bool(profile.hasRailService()));
                statement.setInt(11, bool(profile.hasTrain()));
                statement.setInt(12, bool(profile.hasSubway()));
                statement.setInt(13, bool(profile.hasTram()));
                statement.setInt(14, bool(profile.hasBus()));
                statement.setInt(15, bool(profile.busOnly()));
                statement.setInt(16, bool(profile.stationNameSignal()));
                statement.setInt(17, bool(profile.mainStationSignal()));
                statement.setInt(18, profile.searchPriorityScore());
                statement.setString(19, profile.explanation());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeCanonicalStopAreas(
            List<CanonicalStopArea> canonicalAreas,
            List<CanonicalStopAreaMember> members,
            List<CanonicalStopAreaTransferEdge> transferEdges,
            List<StopArea> stopAreas,
            List<StopAreaCity> stopAreaCities
    ) throws SQLException {
        try (PreparedStatement areaStatement = connection.prepareStatement("""
                INSERT INTO canonical_stop_areas(
                    canonical_area_id,
                    canonical_display_name,
                    original_name,
                    city_name,
                    station_name,
                    name_order,
                    primary_stop_area_id,
                    profile_class,
                    has_rail_service,
                    line_labels,
                    member_count,
                    display_quality,
                    source,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement memberStatement = connection.prepareStatement("""
                     INSERT INTO canonical_stop_area_members(
                         canonical_area_id,
                         area_id,
                         member_role,
                         display_role,
                         is_primary_for_search,
                         is_primary_for_routing,
                         is_visible_suggestion,
                         access_cost_minutes,
                         quality,
                         distance_meters,
                         source,
                         explanation
                     )
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement edgeStatement = connection.prepareStatement("""
                     INSERT INTO canonical_stop_area_transfer_edges(
                         canonical_area_id,
                         from_area_id,
                         to_area_id,
                         distance_meters,
                         min_transfer_minutes,
                         quality,
                         source,
                         explanation
                     )
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement nameStatement = connection.prepareStatement("""
                     INSERT INTO canonical_stop_area_names(
                         canonical_area_id,
                         original_name,
                         display_name,
                         display_name_normalized,
                         city_name,
                         station_name,
                         name_order,
                         display_quality,
                         source,
                         explanation
                     )
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement displayNameStatement = connection.prepareStatement("""
                     INSERT OR REPLACE INTO stop_area_display_names(
                         area_id,
                         canonical_area_id,
                         public_display_name,
                         public_display_name_normalized,
                         public_stop_name,
                         public_city_name,
                         display_quality,
                         source,
                         explanation
                     )
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement aliasStatement = connection.prepareStatement("""
                     INSERT OR IGNORE INTO stop_area_aliases(area_id, alias, alias_normalized, alias_type, source, priority)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            begin();
            int batchedAreas = 0;
            for (CanonicalStopArea area : canonicalAreas) {
                areaStatement.setString(1, area.canonicalAreaId());
                areaStatement.setString(2, area.canonicalDisplayName());
                areaStatement.setString(3, area.originalName());
                areaStatement.setString(4, area.cityName());
                areaStatement.setString(5, area.stationName());
                areaStatement.setString(6, area.nameOrder());
                areaStatement.setString(7, area.primaryStopAreaId());
                areaStatement.setString(8, area.profileClass());
                areaStatement.setInt(9, bool(area.hasRailService()));
                areaStatement.setString(10, area.lineLabels());
                areaStatement.setInt(11, area.memberCount());
                areaStatement.setString(12, area.displayQuality());
                areaStatement.setString(13, area.source());
                areaStatement.setString(14, area.explanation());
                areaStatement.addBatch();
                batchedAreas = executeCanonicalBatchIfNeeded(
                        areaStatement,
                        batchedAreas,
                        "canonical_stop_areas"
                );
            }
            areaStatement.executeBatch();
            compactCanonicalWriteHeap("canonical_stop_areas", areaStatement);

            int batchedNames = 0;
            for (CanonicalStopArea area : canonicalAreas) {
                nameStatement.setString(1, area.canonicalAreaId());
                nameStatement.setString(2, area.originalName());
                nameStatement.setString(3, area.canonicalDisplayName());
                nameStatement.setString(4, StopNameNormalizer.normalize(area.canonicalDisplayName()));
                nameStatement.setString(5, area.cityName());
                nameStatement.setString(6, area.stationName());
                nameStatement.setString(7, area.nameOrder());
                nameStatement.setString(8, area.displayQuality());
                nameStatement.setString(9, area.source());
                nameStatement.setString(10, area.explanation());
                nameStatement.addBatch();
                batchedNames = executeCanonicalBatchIfNeeded(
                        nameStatement,
                        batchedNames,
                        "canonical_stop_area_names"
                );
            }
            nameStatement.executeBatch();
            compactCanonicalWriteHeap("canonical_stop_area_names", nameStatement);

            int batchedDisplayNames = 0;
            for (StopAreaDisplayName displayName : displayNamesFrom(
                    canonicalAreas,
                    members,
                    stopAreas,
                    stopAreaCities
            )) {
                displayNameStatement.setString(1, displayName.areaId());
                displayNameStatement.setString(2, displayName.canonicalAreaId());
                displayNameStatement.setString(3, displayName.publicDisplayName());
                displayNameStatement.setString(4, displayName.publicDisplayNameNormalized());
                displayNameStatement.setString(5, displayName.publicStopName());
                displayNameStatement.setString(6, displayName.publicCityName());
                displayNameStatement.setString(7, displayName.displayQuality());
                displayNameStatement.setString(8, displayName.source());
                displayNameStatement.setString(9, displayName.explanation());
                displayNameStatement.addBatch();
                batchedDisplayNames = executeCanonicalBatchIfNeeded(
                        displayNameStatement,
                        batchedDisplayNames,
                        "stop_area_display_names"
                );
            }
            displayNameStatement.executeBatch();
            compactCanonicalWriteHeap("stop_area_display_names", displayNameStatement);

            int batchedCanonicalAliases = 0;
            for (CanonicalStopArea area : canonicalAreas) {
                batchedCanonicalAliases = addCanonicalFamilyAlias(
                        aliasStatement,
                        batchedCanonicalAliases,
                        area.primaryStopAreaId(),
                        area.canonicalDisplayName(),
                        115
                );
                String normalized = StopNameNormalizer.normalize(area.canonicalDisplayName());
                if (normalized.contains("hauptbahnhof")) {
                    batchedCanonicalAliases = addCanonicalFamilyAlias(
                            aliasStatement,
                            batchedCanonicalAliases,
                            area.primaryStopAreaId(),
                            area.canonicalDisplayName().replaceAll("(?i)hauptbahnhof", "Hbf"),
                            108
                    );
                }
                if (containsToken(normalized, "hbf")) {
                    batchedCanonicalAliases = addCanonicalFamilyAlias(
                            aliasStatement,
                            batchedCanonicalAliases,
                            area.primaryStopAreaId(),
                            area.canonicalDisplayName().replaceAll("(?i)\\bhbf\\b", "Hauptbahnhof"),
                            108
                    );
                }
                if (normalized.contains("bahnhof")) {
                    batchedCanonicalAliases = addCanonicalFamilyAlias(
                            aliasStatement,
                            batchedCanonicalAliases,
                            area.primaryStopAreaId(),
                            area.canonicalDisplayName().replaceAll("(?i)bahnhof", "Bf"),
                            104
                    );
                }
                if (containsToken(normalized, "bf")) {
                    batchedCanonicalAliases = addCanonicalFamilyAlias(
                            aliasStatement,
                            batchedCanonicalAliases,
                            area.primaryStopAreaId(),
                            area.canonicalDisplayName().replaceAll("(?i)\\bbf\\b", "Bahnhof"),
                            104
                    );
                }
            }
            aliasStatement.executeBatch();
            compactCanonicalWriteHeap("canonical_stop_area_aliases", aliasStatement);

            int batchedMembers = 0;
            for (CanonicalStopAreaMember member : members) {
                memberStatement.setString(1, member.canonicalAreaId());
                memberStatement.setString(2, member.areaId());
                memberStatement.setString(3, member.memberRole());
                memberStatement.setString(4, member.displayRole());
                memberStatement.setInt(5, bool(member.primaryForSearch()));
                memberStatement.setInt(6, bool(member.primaryForRouting()));
                memberStatement.setInt(7, bool(member.visibleSuggestion()));
                memberStatement.setInt(8, member.accessCostMinutes());
                memberStatement.setString(9, member.quality());
                setInteger(memberStatement, 10, member.distanceMeters());
                memberStatement.setString(11, member.source());
                memberStatement.setString(12, member.explanation());
                memberStatement.addBatch();
                batchedMembers = executeCanonicalBatchIfNeeded(
                        memberStatement,
                        batchedMembers,
                        "canonical_stop_area_members"
                );
            }
            memberStatement.executeBatch();
            compactCanonicalWriteHeap("canonical_stop_area_members", memberStatement);

            int batchedEdges = 0;
            for (CanonicalStopAreaTransferEdge edge : transferEdges) {
                edgeStatement.setString(1, edge.canonicalAreaId());
                edgeStatement.setString(2, edge.fromAreaId());
                edgeStatement.setString(3, edge.toAreaId());
                setInteger(edgeStatement, 4, edge.distanceMeters());
                edgeStatement.setInt(5, edge.minTransferMinutes());
                edgeStatement.setString(6, edge.quality());
                edgeStatement.setString(7, edge.source());
                edgeStatement.setString(8, edge.explanation());
                edgeStatement.addBatch();
                batchedEdges = executeCanonicalBatchIfNeeded(
                        edgeStatement,
                        batchedEdges,
                        "canonical_stop_area_transfer_edges"
                );
            }
            edgeStatement.executeBatch();
            commit();
        }
    }

    public void writeAreaRouteServiceSummaries(List<AreaRouteServiceSummary> summaries) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO area_route_service_summary(
                    area_id,
                    route_id,
                    route_type,
                    line_label,
                    stop_time_count,
                    trip_count
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (AreaRouteServiceSummary summary : summaries) {
                statement.setString(1, summary.areaId());
                statement.setString(2, summary.routeId());
                setInteger(statement, 3, summary.routeType());
                statement.setString(4, summary.lineLabel());
                statement.setInt(5, summary.stopTimeCount());
                statement.setInt(6, summary.tripCount());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public RouteAxisBuilder.RouteAxisStats writeRouteAxes(RouteAxisBuilder builder,
            GtfsCsvReader.ProgressListener readProgress,
            GtfsCsvReader.ProgressListener writeProgress) throws SQLException {
        try (PreparedStatement axisStatement = connection.prepareStatement("""
                INSERT INTO route_axes(
                    axis_id,
                    route_id,
                    direction_id,
                    representative_trip_id,
                    trip_count,
                    stop_count,
                    first_area_id,
                    last_area_id,
                    route_short_name,
                    route_long_name,
                    route_type,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
             PreparedStatement stopStatement = connection.prepareStatement("""
                     INSERT INTO route_axis_stops(axis_id, sequence_index, area_id)
                     VALUES (?, ?, ?)
                     """)) {
            begin();
            int[] batched = new int[2];
            long[] rows = new long[1];
            RouteAxisBuilder.RouteAxisStats stats = builder.streamFromDatabase(databasePath, (axis, sequence) -> {
                axisStatement.setString(1, axis.axisId());
                axisStatement.setString(2, axis.routeId());
                axisStatement.setString(3, axis.directionId());
                axisStatement.setString(4, axis.representativeTripId());
                axisStatement.setInt(5, axis.tripCount());
                axisStatement.setInt(6, axis.stopCount());
                axisStatement.setString(7, axis.firstAreaId());
                axisStatement.setString(8, axis.lastAreaId());
                axisStatement.setString(9, axis.routeShortName());
                axisStatement.setString(10, axis.routeLongName());
                setInteger(axisStatement, 11, axis.routeType());
                axisStatement.setString(12, axis.explanation());
                axisStatement.addBatch();
                batched[0] = executeBatchIfNeeded(axisStatement, batched[0]);
                for (int index = 0; index < sequence.size(); index++) {
                    stopStatement.setString(1, axis.axisId());
                    stopStatement.setInt(2, index);
                    stopStatement.setString(3, sequence.get(index));
                    stopStatement.addBatch();
                    batched[1] = executeBatchIfNeeded(stopStatement, batched[1]);
                    if (writeProgress != null) writeProgress.onRowsRead(++rows[0]);
                }
            }, readProgress);
            axisStatement.executeBatch();
            stopStatement.executeBatch();
            commit();
            return stats;
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    public void writeTransferRules(List<TransferRule> rules) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transfer_rules(
                    transfer_rule_id,
                    raw_transfer_id,
                    from_area_id,
                    to_area_id,
                    from_stop_id,
                    to_stop_id,
                    transfer_type,
                    min_transfer_time_seconds,
                    transfer_semantic,
                    scope_type,
                    pedestrian_usable,
                    source,
                    confidence,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (TransferRule rule : rules) {
                statement.setString(1, rule.transferRuleId());
                setLong(statement, 2, rule.rawTransferId());
                statement.setString(3, rule.fromAreaId());
                statement.setString(4, rule.toAreaId());
                statement.setString(5, rule.fromStopId());
                statement.setString(6, rule.toStopId());
                setInteger(statement, 7, rule.transferType());
                setInteger(statement, 8, rule.minTransferTimeSeconds());
                statement.setString(9, rule.transferSemantic());
                statement.setString(10, rule.scopeType());
                statement.setInt(11, rule.pedestrianUsable() ? 1 : 0);
                statement.setString(12, rule.source());
                statement.setString(13, rule.confidence());
                statement.setString(14, rule.explanation());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public void writeTransferEdges(List<TransferEdge> edges) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transfer_edges(
                    transfer_edge_id,
                    raw_transfer_id,
                    from_stop_area_id,
                    to_stop_area_id,
                    from_stop_id,
                    to_stop_id,
                    distance_meters,
                    min_transfer_seconds,
                    min_transfer_minutes,
                    is_traversable,
                    edge_kind,
                    transfer_semantic,
                    scope_type,
                    distance_model,
                    quality,
                    source,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (TransferEdge edge : edges) {
                bindTransferEdge(statement, edge);
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public TransferEdgeBuilder.TransferEdgeStats writeTransferEdges(
            TransferEdgeBuilder builder,
            List<TransferRule> rules
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO transfer_edges(
                    transfer_edge_id,
                    raw_transfer_id,
                    from_stop_area_id,
                    to_stop_area_id,
                    from_stop_id,
                    to_stop_id,
                    distance_meters,
                    min_transfer_seconds,
                    min_transfer_minutes,
                    is_traversable,
                    edge_kind,
                    transfer_semantic,
                    scope_type,
                    distance_model,
                    quality,
                    source,
                    explanation
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            BatchCounter counter = new BatchCounter();
            RowCounter transactionRows = new RowCounter();
            TransferEdgeBuilder.TransferEdgeStats stats = builder.writeTo(rules, edge -> {
                try {
                    bindTransferEdge(statement, edge);
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                    transactionRows.count++;
                    if (transactionRows.count >= STOP_TIMES_COMMIT_ROWS) {
                        statement.executeBatch();
                        counter.count = 0;
                        commit();
                        begin();
                        transactionRows.count = 0;
                    }
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            });
            statement.executeBatch();
            commit();
            return stats;
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    public void writePathways(List<de.ixit.gtfs.model.Pathway> pathways) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO pathways(pathway_id, from_stop_id, to_stop_id, pathway_mode,
                    is_bidirectional, length, traversal_time, stair_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            int batched = 0;
            for (var path : pathways) {
                statement.setString(1, path.pathwayId());
                statement.setString(2, path.fromStopId());
                statement.setString(3, path.toStopId());
                setInteger(statement, 4, path.mode());
                setInteger(statement, 5, path.bidirectional());
                statement.setObject(6, path.lengthMeters());
                setInteger(statement, 7, path.traversalSeconds());
                setInteger(statement, 8, path.stairCount());
                statement.addBatch();
                batched = executeBatchIfNeeded(statement, batched);
            }
            statement.executeBatch();
            commit();
        }
    }

    public StopFootpathBuilder.StopFootpathStats writeStopFootpaths(StopFootpathBuilder builder) throws SQLException {
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO stop_footpaths(
                    footpath_id,
                    area_id,
                    from_stop_id,
                    to_stop_id,
                    distance_meters,
                    min_transfer_seconds,
                    is_traversable,
                    quality,
                    distance_model,
                    time_model,
                    source,
                    explanation,
                    walk_seconds,
                    transfer_buffer_seconds,
                    gtfs_min_transfer_seconds,
                    pathway_ids,
                    pathway_modes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            begin();
            BatchCounter counter = new BatchCounter();
            RowCounter transactionRows = new RowCounter();
            StopFootpathBuilder.StopFootpathStats stats = builder.writeTo(footpath -> {
                try {
                    statement.setString(1, footpath.footpathId());
                    statement.setString(2, footpath.areaId());
                    statement.setString(3, footpath.fromStopId());
                    statement.setString(4, footpath.toStopId());
                    setInteger(statement, 5, footpath.distanceMeters());
                    setInteger(statement, 6, footpath.minTransferSeconds());
                    statement.setInt(7, footpath.traversable() ? 1 : 0);
                    statement.setString(8, footpath.quality());
                    statement.setString(9, footpath.distanceModel());
                    statement.setString(10, footpath.timeModel());
                    statement.setString(11, footpath.source());
                    statement.setString(12, footpath.explanation());
                    setInteger(statement, 13, footpath.walkSeconds());
                    setInteger(statement, 14, footpath.transferBufferSeconds());
                    setInteger(statement, 15, footpath.gtfsMinTransferSeconds());
                    try {
                        statement.setString(16, json.writeValueAsString(footpath.pathwayIds()));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                        throw new SQLException("Cannot serialize footpath provenance", ex);
                    }
                    statement.setInt(17, footpath.pathwayModes());
                    statement.addBatch();
                    counter.count = executeBatchIfNeeded(statement, counter.count);
                    transactionRows.count++;
                    if (transactionRows.count >= STOP_TIMES_COMMIT_ROWS) {
                        statement.executeBatch();
                        counter.count = 0;
                        commit();
                        begin();
                        transactionRows.count = 0;
                    }
                } catch (SQLException ex) {
                    throw new GtfsWriteException(ex);
                }
            });
            statement.executeBatch();
            commit();
            return stats;
        } catch (GtfsWriteException ex) {
            throw ex.sqlException;
        }
    }

    private static void bindTransferEdge(PreparedStatement statement, TransferEdge edge) throws SQLException {
        statement.setString(1, edge.transferEdgeId());
        setLong(statement, 2, edge.rawTransferId());
        statement.setString(3, edge.fromStopAreaId());
        statement.setString(4, edge.toStopAreaId());
        statement.setString(5, edge.fromStopId());
        statement.setString(6, edge.toStopId());
        setInteger(statement, 7, edge.distanceMeters());
        statement.setInt(8, edge.minTransferSeconds());
        statement.setInt(9, edge.minTransferMinutes());
        statement.setInt(10, edge.traversable() ? 1 : 0);
        statement.setString(11, edge.edgeKind());
        statement.setString(12, edge.transferSemantic());
        statement.setString(13, edge.scopeType());
        statement.setString(14, edge.distanceModel());
        statement.setString(15, edge.quality());
        statement.setString(16, edge.source());
        statement.setString(17, edge.explanation());
    }

    public Map<String, String> writeMetadata(
            Optional<FeedInfo> feedInfo,
            MetadataDiagnostics diagnostics,
            String contractVersion,
            BuildIdentity buildIdentity
    ) throws SQLException {
        if (buildIdentity == null) {
            throw new IllegalArgumentException("Build identity is required for SQLite metadata");
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("schema_version", SqliteContract.SCHEMA_VERSION);
        metadata.put("preprocessor_version", SqliteContract.PREPROCESSOR_VERSION);
        metadata.put("generated_at", Instant.now().toString());
        String feedName = feedInfo.map(FeedInfo::sourceFeedName)
                .filter(value -> !value.isBlank())
                .orElse("unknown");
        feedInfo.map(FeedInfo::sourceFeedName).filter(value -> !value.isBlank()).ifPresent(value -> metadata.put("source_feed_name", value));
        feedInfo.map(FeedInfo::sourceFeedVersion).filter(value -> !value.isBlank()).ifPresent(value -> metadata.put("source_feed_version", value));
        metadata.put("contract_name", SqliteContract.CONTRACT_NAME);
        metadata.put("contract_version", contractVersion == null || contractVersion.isBlank() ? SqliteContract.CONTRACT_VERSION : contractVersion);
        metadata.put("time_model", SqliteContract.TIME_MODEL);
        metadata.put("stop_id_policy", SqliteContract.STOP_ID_POLICY);
        metadata.put("area_id_policy", SqliteContract.AREA_ID_POLICY);
        metadata.put("search_tokens_policy", SqliteContract.SEARCH_TOKENS_POLICY);
        metadata.put("display_name_transformation_version", SqliteContract.DISPLAY_NAME_TRANSFORMATION_VERSION);
        metadata.put("display_name_transformation_policy", SqliteContract.DISPLAY_NAME_TRANSFORMATION_POLICY);
        metadata.put("service_day_model_version", ServiceDayModelAuditor.MODEL_VERSION);
        metadata.put("service_day_resolution_policy", SqliteContract.SERVICE_DAY_RESOLUTION_POLICY);
        metadata.put("service_day_timezone_policy", SqliteContract.SERVICE_DAY_TIMEZONE_POLICY);
        metadata.put("service_day_time_overflow_policy", SqliteContract.SERVICE_DAY_TIME_OVERFLOW_POLICY);
        metadata.put("transfer_semantics_policy", SqliteContract.TRANSFER_SEMANTICS_POLICY);
        metadata.put("footpath_policy", SqliteContract.FOOTPATH_POLICY);
        metadata.put("walk_model_version", SqliteContract.WALK_MODEL_VERSION);
        metadata.put("feed_timezones", readFeedTimezones());
        metadata.putAll(buildIdentity.metadata());
        metadata.put("source_format", "GTFS");
        metadata.put("feed_name", feedName);
        metadata.put("feed_region", "unknown");
        metadata.put("source_file_size", Long.toString(diagnostics.sourceFileSize()));
        metadata.put("stop_count", Long.toString(diagnostics.stopCount()));
        metadata.put("stop_area_count", Long.toString(diagnostics.stopAreaCount()));
        metadata.put("trip_count", Long.toString(diagnostics.tripCount()));
        metadata.put("stop_time_count", Long.toString(diagnostics.stopTimeCount()));
        metadata.put("shape_point_count", Long.toString(diagnostics.shapePointCount()));
        metadata.put("calendar_count", Long.toString(diagnostics.calendarCount()));
        metadata.put("calendar_dates_count", Long.toString(diagnostics.calendarDatesCount()));
        metadata.put("route_count", Long.toString(diagnostics.routeCount()));
        metadata.put("stop_area_city_official_boundary_count", Long.toString(diagnostics.officialBoundaryCityCount()));
        metadata.put("stop_area_city_name_fallback_count", Long.toString(diagnostics.nameFallbackCityCount()));
        metadata.put("stop_area_city_unresolved_count", Long.toString(diagnostics.unresolvedCityCount()));
        if (diagnostics.municipalityDataVersion() != null && !diagnostics.municipalityDataVersion().isBlank()) {
            metadata.put("municipality_data_source", "BKG_VG250");
            metadata.put("municipality_data_version", diagnostics.municipalityDataVersion());
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ixit_metadata(key, value)
                VALUES (?, ?)
                """)) {
            begin();
            for (Map.Entry<String, String> entry : metadata.entrySet()) {
                statement.setString(1, entry.getKey());
                statement.setString(2, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
            commit();
        }
        return Map.copyOf(metadata);
    }

    public record MetadataDiagnostics(
            long sourceFileSize,
            long stopCount,
            long stopAreaCount,
            long tripCount,
            long stopTimeCount,
            long shapePointCount,
            long calendarCount,
            long calendarDatesCount,
            long routeCount,
            long officialBoundaryCityCount,
            long nameFallbackCityCount,
            long unresolvedCityCount,
            String municipalityDataVersion
    ) {
    }

    public void createIndexes() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createRouteAxisSourceIndexes(statement);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stops_parent_station ON stops(parent_station)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_members_area_id ON stop_area_members(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_trips_route_id ON trips(route_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_trips_service_id ON trips(service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_stop_departure ON stop_times(stop_id, departure_seconds)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_stop_trip ON stop_times(stop_id, trip_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_from_to ON transfers(from_stop_id, to_stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_type ON transfers(transfer_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_trip_scope ON transfers(from_trip_id, to_trip_id, service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_service_id ON calendar(service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_dates_service_id ON calendar_dates(service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_dates_date ON calendar_dates(date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_dates_service_date ON calendar_dates(service_id, date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_service_calendar_status ON service_calendar_summary(status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_service_calendar_timezone ON service_calendar_summary(service_timezone)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_service_calendar_trip_count ON service_calendar_summary(trip_count)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_token ON stop_search_tokens(token)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_stop_id ON stop_search_tokens(stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_area_id ON stop_search_tokens(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_token_area ON stop_search_tokens(token, area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_area_id ON stop_area_aliases(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_normalized ON stop_area_aliases(alias_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_area_normalized ON stop_area_aliases(area_id, alias_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_priority ON stop_area_aliases(priority)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_area_route_service_summary_area_id ON area_route_service_summary(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_area_route_service_summary_route_id ON area_route_service_summary(route_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_area_route_service_summary_route_type ON area_route_service_summary(route_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_profiles_profile_class ON stop_area_profiles(profile_class)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_profiles_rail_service ON stop_area_profiles(has_rail_service)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_profiles_bus_only ON stop_area_profiles(bus_only)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_profiles_search_priority ON stop_area_profiles(search_priority_score)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_cities_city ON stop_area_cities(city_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_cities_municipality ON stop_area_cities(municipality_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_cities_quality ON stop_area_cities(quality)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_areas_display_name ON canonical_stop_areas(canonical_display_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_areas_primary_area ON canonical_stop_areas(primary_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_names_display_name ON canonical_stop_area_names(display_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_names_normalized ON canonical_stop_area_names(display_name_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_names_city_name ON canonical_stop_area_names(city_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_display_names_canonical ON stop_area_display_names(canonical_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_display_names_public ON stop_area_display_names(public_display_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_display_names_normalized ON stop_area_display_names(public_display_name_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_display_names_city ON stop_area_display_names(public_city_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_type ON display_name_quality_findings(finding_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_classification ON display_name_quality_findings(classification)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_prefix ON display_name_quality_findings(prefix)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_area ON display_name_quality_findings(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_area_id ON canonical_stop_area_members(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_canonical ON canonical_stop_area_members(canonical_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_role ON canonical_stop_area_members(member_role)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_search ON canonical_stop_area_members(is_primary_for_search)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_routing ON canonical_stop_area_members(is_primary_for_routing)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_transfer_edges_from ON canonical_stop_area_transfer_edges(from_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_transfer_edges_to ON canonical_stop_area_transfer_edges(to_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_transfer_edges_family ON canonical_stop_area_transfer_edges(canonical_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_hub_profiles_hub_level ON hub_profiles(hub_level)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_hub_profiles_route_count ON hub_profiles(route_count)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_hub_profiles_trip_count ON hub_profiles(trip_count)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_hub_profiles_transfer_candidate_score ON hub_profiles(transfer_candidate_score)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axes_route_id ON route_axes(route_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axes_direction_id ON route_axes(direction_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axes_route_direction ON route_axes(route_id, direction_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axes_first_area_id ON route_axes(first_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axes_last_area_id ON route_axes(last_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axis_stops_area_id ON route_axis_stops(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_route_axis_stops_axis_id ON route_axis_stops(axis_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_from_area_id ON transfer_rules(from_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_to_area_id ON transfer_rules(to_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_from_to_area ON transfer_rules(from_area_id, to_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_source ON transfer_rules(source)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_confidence ON transfer_rules(confidence)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_semantic ON transfer_rules(transfer_semantic)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_scope ON transfer_rules(scope_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_pedestrian ON transfer_rules(pedestrian_usable)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_rules_raw ON transfer_rules(raw_transfer_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_from_area ON transfer_edges(from_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_to_area ON transfer_edges(to_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_from_to_area ON transfer_edges(from_stop_area_id, to_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_quality ON transfer_edges(quality)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_source ON transfer_edges(source)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_traversable ON transfer_edges(is_traversable)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_kind ON transfer_edges(edge_kind)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_raw ON transfer_edges(raw_transfer_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_area ON stop_footpaths(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_from ON stop_footpaths(from_stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_to ON stop_footpaths(to_stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_traversable ON stop_footpaths(is_traversable)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_quality ON stop_footpaths(quality)");
        }
    }

    public void createAppRuntimeIndexes() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stops_parent_station ON stops(parent_station)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_members_area_id ON stop_area_members(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_members_stop_id ON stop_area_members(stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_from_to ON transfers(from_stop_id, to_stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_type ON transfers(transfer_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfers_trip_scope ON transfers(from_trip_id, to_trip_id, service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_trips_service_id ON trips(service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_trip_sequence ON stop_times(trip_id, stop_sequence)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_stop_departure ON stop_times(stop_id, departure_seconds)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_service_id ON calendar(service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_dates_service_id ON calendar_dates(service_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_dates_date ON calendar_dates(date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calendar_dates_service_date ON calendar_dates(service_id, date)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_service_calendar_status ON service_calendar_summary(status)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_service_calendar_timezone ON service_calendar_summary(service_timezone)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_service_calendar_trip_count ON service_calendar_summary(trip_count)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_token ON stop_search_tokens(token)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_area_id ON stop_search_tokens(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_search_tokens_token_area ON stop_search_tokens(token, area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_area_id ON stop_area_aliases(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_normalized ON stop_area_aliases(alias_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_aliases_area_normalized ON stop_area_aliases(area_id, alias_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_cities_city ON stop_area_cities(city_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_cities_municipality ON stop_area_cities(municipality_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_areas_display_name ON canonical_stop_areas(canonical_display_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_areas_primary_area ON canonical_stop_areas(primary_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_names_display_name ON canonical_stop_area_names(display_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_names_normalized ON canonical_stop_area_names(display_name_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_display_names_canonical ON stop_area_display_names(canonical_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_display_names_normalized ON stop_area_display_names(public_display_name_normalized)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_type ON display_name_quality_findings(finding_type)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_classification ON display_name_quality_findings(classification)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_prefix ON display_name_quality_findings(prefix)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_display_name_quality_area ON display_name_quality_findings(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_area_id ON canonical_stop_area_members(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_canonical ON canonical_stop_area_members(canonical_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_search ON canonical_stop_area_members(is_primary_for_search)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_members_routing ON canonical_stop_area_members(is_primary_for_routing)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_transfer_edges_from ON canonical_stop_area_transfer_edges(from_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_transfer_edges_to ON canonical_stop_area_transfer_edges(to_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_canonical_stop_area_transfer_edges_family ON canonical_stop_area_transfer_edges(canonical_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_from_area ON transfer_edges(from_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_to_area ON transfer_edges(to_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_from_to_area ON transfer_edges(from_stop_area_id, to_stop_area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_traversable ON transfer_edges(is_traversable)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_kind ON transfer_edges(edge_kind)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_transfer_edges_raw ON transfer_edges(raw_transfer_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_area ON stop_footpaths(area_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_from ON stop_footpaths(from_stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_to ON stop_footpaths(to_stop_id)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_footpaths_traversable ON stop_footpaths(is_traversable)");
        }
    }

    public void createRouteAxisSourceIndexes() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            createRouteAxisSourceIndexes(statement);
        }
    }

    private static void createRouteAxisSourceIndexes(Statement statement) throws SQLException {
        statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_area_members_stop_id ON stop_area_members(stop_id)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_trip_sequence ON stop_times(trip_id, stop_sequence)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_stop_departure ON stop_times(stop_id, departure_seconds)");
        statement.execute("CREATE INDEX IF NOT EXISTS idx_stop_times_stop_trip ON stop_times(stop_id, trip_id)");
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private void configure() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode = " + SQLITE_JOURNAL_MODE);
            statement.execute("PRAGMA synchronous = " + SQLITE_SYNCHRONOUS);
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("PRAGMA wal_autocheckpoint = 10000");
            if (!SQLITE_TEMP_STORE.isBlank()) {
                statement.execute("PRAGMA temp_store = " + SQLITE_TEMP_STORE);
            }
            if (!SQLITE_CACHE_SIZE.isBlank()) {
                statement.execute("PRAGMA cache_size = " + SQLITE_CACHE_SIZE);
            }
            if (!SQLITE_LOCKING_MODE.isBlank()) {
                statement.execute("PRAGMA locking_mode = " + SQLITE_LOCKING_MODE);
            }
            activePragmas = readActivePragmas(statement);
        }
    }

    private static Map<String, String> readActivePragmas(Statement statement) throws SQLException {
        Map<String, String> pragmas = new LinkedHashMap<>();
        pragmas.put("journal_mode", queryPragma(statement, "journal_mode"));
        pragmas.put("synchronous", synchronousName(queryPragma(statement, "synchronous")));
        pragmas.put("temp_store", tempStoreName(queryPragma(statement, "temp_store")));
        pragmas.put("cache_size", queryPragma(statement, "cache_size"));
        pragmas.put("locking_mode", queryPragma(statement, "locking_mode"));
        pragmas.put("wal_autocheckpoint", queryPragma(statement, "wal_autocheckpoint"));
        pragmas.put("foreign_keys", queryPragma(statement, "foreign_keys"));
        return Map.copyOf(pragmas);
    }

    private static String queryPragma(Statement statement, String name) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery("PRAGMA " + name)) {
            if (resultSet.next()) {
                return resultSet.getString(1);
            }
        }
        return "";
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE stops (
                        stop_id TEXT PRIMARY KEY,
                        stop_code TEXT,
                        stop_name TEXT,
                        stop_name_normalized TEXT,
                        stop_lat REAL,
                        stop_lon REAL,
                        parent_station TEXT,
                        location_type INTEGER,
                        platform_code TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_areas (
                        area_id TEXT PRIMARY KEY,
                        area_name TEXT,
                        area_name_normalized TEXT,
                        area_lat REAL,
                        area_lon REAL,
                        stop_count INTEGER NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_area_members (
                        area_id TEXT NOT NULL,
                        stop_id TEXT NOT NULL,
                        member_role TEXT NOT NULL,
                        PRIMARY KEY (area_id, stop_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE feed_agencies (
                        agency_key TEXT PRIMARY KEY,
                        agency_id TEXT,
                        agency_name TEXT NOT NULL,
                        agency_timezone TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE routes (
                        route_id TEXT PRIMARY KEY,
                        agency_id TEXT,
                        route_short_name TEXT,
                        route_long_name TEXT,
                        route_type INTEGER,
                        route_color TEXT,
                        route_text_color TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE trips (
                        trip_id TEXT PRIMARY KEY,
                        route_id TEXT NOT NULL,
                        service_id TEXT NOT NULL,
                        trip_headsign TEXT,
                        direction_id TEXT,
                        block_id TEXT,
                        shape_id TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_times (
                        trip_id TEXT NOT NULL,
                        arrival_seconds INTEGER NOT NULL,
                        departure_seconds INTEGER NOT NULL,
                        stop_id TEXT NOT NULL,
                        stop_sequence INTEGER NOT NULL,
                        pickup_type INTEGER,
                        drop_off_type INTEGER,
                        shape_dist_traveled REAL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE shapes (
                        shape_id TEXT NOT NULL,
                        shape_pt_sequence INTEGER NOT NULL,
                        shape_pt_lat REAL NOT NULL,
                        shape_pt_lon REAL NOT NULL,
                        shape_dist_traveled REAL,
                        PRIMARY KEY (shape_id, shape_pt_sequence)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE transfers (
                        transfer_id INTEGER PRIMARY KEY,
                        from_stop_id TEXT NOT NULL,
                        to_stop_id TEXT NOT NULL,
                        from_route_id TEXT,
                        to_route_id TEXT,
                        from_trip_id TEXT,
                        to_trip_id TEXT,
                        transfer_type INTEGER,
                        min_transfer_time INTEGER,
                        service_id TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE calendar (
                        service_id TEXT PRIMARY KEY,
                        monday INTEGER,
                        tuesday INTEGER,
                        wednesday INTEGER,
                        thursday INTEGER,
                        friday INTEGER,
                        saturday INTEGER,
                        sunday INTEGER,
                        start_date TEXT,
                        end_date TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE calendar_dates (
                        service_id TEXT NOT NULL,
                        date TEXT NOT NULL,
                        exception_type INTEGER,
                        exception_action TEXT NOT NULL,
                        source TEXT NOT NULL,
                        PRIMARY KEY (service_id, date)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE service_calendar_summary (
                        service_id TEXT PRIMARY KEY,
                        has_calendar INTEGER NOT NULL,
                        weekday_mask INTEGER NOT NULL,
                        start_date TEXT,
                        end_date TEXT,
                        addition_count INTEGER NOT NULL,
                        removal_count INTEGER NOT NULL,
                        first_exception_date TEXT,
                        last_exception_date TEXT,
                        trip_count INTEGER NOT NULL,
                        service_timezone TEXT NOT NULL,
                        status TEXT NOT NULL,
                        explanation TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_search_tokens (
                        stop_id TEXT,
                        area_id TEXT NOT NULL,
                        token TEXT NOT NULL,
                        token_type TEXT NOT NULL,
                        source TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_area_aliases (
                        area_id TEXT NOT NULL,
                        alias TEXT NOT NULL,
                        alias_normalized TEXT NOT NULL,
                        alias_type TEXT NOT NULL,
                        source TEXT NOT NULL,
                        priority INTEGER NOT NULL,
                        PRIMARY KEY (area_id, alias_normalized, alias_type, source)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE area_route_service_summary (
                        area_id TEXT NOT NULL,
                        route_id TEXT NOT NULL,
                        route_type INTEGER,
                        line_label TEXT,
                        stop_time_count INTEGER NOT NULL,
                        trip_count INTEGER NOT NULL,
                        PRIMARY KEY (area_id, route_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_area_cities (
                        area_id TEXT PRIMARY KEY,
                        municipality_id TEXT,
                        city_name TEXT,
                        municipality_type TEXT,
                        source TEXT NOT NULL,
                        quality TEXT NOT NULL,
                        data_version TEXT,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_area_profiles (
                        area_id TEXT PRIMARY KEY,
                        profile_class TEXT NOT NULL,
                        stop_count INTEGER NOT NULL,
                        platform_count INTEGER NOT NULL,
                        route_count INTEGER NOT NULL,
                        trip_count INTEGER NOT NULL,
                        stop_time_count INTEGER NOT NULL,
                        route_types TEXT,
                        line_labels TEXT,
                        has_rail_service INTEGER NOT NULL,
                        has_train INTEGER NOT NULL,
                        has_subway INTEGER NOT NULL,
                        has_tram INTEGER NOT NULL,
                        has_bus INTEGER NOT NULL,
                        bus_only INTEGER NOT NULL,
                        station_name_signal INTEGER NOT NULL,
                        main_station_signal INTEGER NOT NULL,
                        search_priority_score INTEGER NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE canonical_stop_areas (
                        canonical_area_id TEXT PRIMARY KEY,
                        canonical_display_name TEXT NOT NULL,
                        original_name TEXT NOT NULL,
                        city_name TEXT,
                        station_name TEXT,
                        name_order TEXT NOT NULL,
                        primary_stop_area_id TEXT NOT NULL,
                        profile_class TEXT NOT NULL,
                        has_rail_service INTEGER NOT NULL,
                        line_labels TEXT,
                        member_count INTEGER NOT NULL,
                        display_quality TEXT NOT NULL,
                        source TEXT NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE canonical_stop_area_members (
                        canonical_area_id TEXT NOT NULL,
                        area_id TEXT NOT NULL,
                        member_role TEXT NOT NULL,
                        display_role TEXT NOT NULL,
                        is_primary_for_search INTEGER NOT NULL,
                        is_primary_for_routing INTEGER NOT NULL,
                        is_visible_suggestion INTEGER NOT NULL,
                        access_cost_minutes INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        distance_meters INTEGER,
                        source TEXT NOT NULL,
                        explanation TEXT,
                        PRIMARY KEY (canonical_area_id, area_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE canonical_stop_area_transfer_edges (
                        canonical_area_id TEXT NOT NULL,
                        from_area_id TEXT NOT NULL,
                        to_area_id TEXT NOT NULL,
                        distance_meters INTEGER,
                        min_transfer_minutes INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        source TEXT NOT NULL,
                        explanation TEXT,
                        PRIMARY KEY (canonical_area_id, from_area_id, to_area_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE canonical_stop_area_names (
                        canonical_area_id TEXT PRIMARY KEY,
                        original_name TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        display_name_normalized TEXT NOT NULL,
                        city_name TEXT,
                        station_name TEXT,
                        name_order TEXT NOT NULL,
                        display_quality TEXT NOT NULL,
                        source TEXT NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_area_display_names (
                        area_id TEXT PRIMARY KEY,
                        canonical_area_id TEXT NOT NULL,
                        public_display_name TEXT NOT NULL,
                        public_display_name_normalized TEXT NOT NULL,
                        public_stop_name TEXT,
                        public_city_name TEXT,
                        display_quality TEXT NOT NULL,
                        source TEXT NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE display_name_quality_findings (
                        area_id TEXT NOT NULL,
                        finding_type TEXT NOT NULL,
                        classification TEXT NOT NULL,
                        prefix TEXT,
                        public_stop_name TEXT NOT NULL,
                        public_city_name TEXT NOT NULL,
                        public_display_name TEXT NOT NULL,
                        action TEXT NOT NULL,
                        rationale TEXT NOT NULL,
                        PRIMARY KEY (area_id, finding_type)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE hub_profiles (
                        area_id TEXT PRIMARY KEY,
                        hub_level TEXT NOT NULL,
                        stop_count INTEGER NOT NULL,
                        route_count INTEGER NOT NULL,
                        trip_count INTEGER NOT NULL,
                        route_type_count INTEGER NOT NULL,
                        stop_time_count INTEGER NOT NULL,
                        has_train INTEGER NOT NULL,
                        has_subway INTEGER NOT NULL,
                        has_tram INTEGER NOT NULL,
                        has_bus INTEGER NOT NULL,
                        has_rail_keyword INTEGER NOT NULL,
                        has_main_station_keyword INTEGER NOT NULL,
                        transfer_candidate_score INTEGER NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE route_axes (
                        axis_id TEXT PRIMARY KEY,
                        route_id TEXT NOT NULL,
                        direction_id TEXT,
                        representative_trip_id TEXT NOT NULL,
                        trip_count INTEGER NOT NULL,
                        stop_count INTEGER NOT NULL,
                        first_area_id TEXT,
                        last_area_id TEXT,
                        route_short_name TEXT,
                        route_long_name TEXT,
                        route_type INTEGER,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE route_axis_stops (
                        axis_id TEXT NOT NULL,
                        sequence_index INTEGER NOT NULL,
                        area_id TEXT NOT NULL,
                        PRIMARY KEY (axis_id, sequence_index)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE transfer_rules (
                        transfer_rule_id TEXT PRIMARY KEY,
                        raw_transfer_id INTEGER,
                        from_area_id TEXT NOT NULL,
                        to_area_id TEXT NOT NULL,
                        from_stop_id TEXT,
                        to_stop_id TEXT,
                        transfer_type INTEGER,
                        min_transfer_time_seconds INTEGER,
                        transfer_semantic TEXT NOT NULL,
                        scope_type TEXT NOT NULL,
                        pedestrian_usable INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        confidence TEXT NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE transfer_edges (
                        transfer_edge_id TEXT PRIMARY KEY,
                        raw_transfer_id INTEGER,
                        from_stop_area_id TEXT NOT NULL,
                        to_stop_area_id TEXT NOT NULL,
                        from_stop_id TEXT,
                        to_stop_id TEXT,
                        distance_meters INTEGER,
                        min_transfer_seconds INTEGER NOT NULL,
                        min_transfer_minutes INTEGER NOT NULL,
                        is_traversable INTEGER NOT NULL,
                        edge_kind TEXT NOT NULL,
                        transfer_semantic TEXT NOT NULL,
                        scope_type TEXT NOT NULL,
                        distance_model TEXT NOT NULL,
                        quality TEXT NOT NULL,
                        source TEXT NOT NULL,
                        explanation TEXT
                    )
                    """);
            statement.execute("""
                    CREATE TABLE stop_footpaths (
                        footpath_id TEXT PRIMARY KEY,
                        area_id TEXT NOT NULL,
                        from_stop_id TEXT NOT NULL,
                        to_stop_id TEXT NOT NULL,
                        distance_meters INTEGER,
                        min_transfer_seconds INTEGER,
                        is_traversable INTEGER NOT NULL,
                        quality TEXT NOT NULL,
                        distance_model TEXT NOT NULL,
                        time_model TEXT NOT NULL,
                        source TEXT NOT NULL,
                        explanation TEXT,
                        walk_seconds INTEGER,
                        transfer_buffer_seconds INTEGER,
                        gtfs_min_transfer_seconds INTEGER,
                        pathway_ids TEXT NOT NULL,
                        pathway_modes INTEGER NOT NULL,
                        UNIQUE (area_id, from_stop_id, to_stop_id)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE pathways (
                        pathway_id TEXT PRIMARY KEY,
                        from_stop_id TEXT NOT NULL,
                        to_stop_id TEXT NOT NULL,
                        pathway_mode INTEGER,
                        is_bidirectional INTEGER,
                        length REAL,
                        traversal_time INTEGER,
                        stair_count INTEGER
                    )
                    """);
            statement.execute("CREATE INDEX idx_pathways_from_to ON pathways(from_stop_id, to_stop_id)");
            statement.execute("""
                    CREATE TABLE ixit_metadata (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
        }
    }

    private void begin() throws SQLException {
        connection.setAutoCommit(false);
    }

    private void commit() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    private static List<StopAreaDisplayName> displayNamesFrom(
            List<CanonicalStopArea> canonicalAreas,
            List<CanonicalStopAreaMember> members,
            List<StopArea> stopAreas,
            List<StopAreaCity> stopAreaCities
    ) {
        Map<String, CanonicalStopArea> areasById = new LinkedHashMap<>();
        for (CanonicalStopArea area : canonicalAreas) {
            areasById.put(area.canonicalAreaId(), area);
        }
        Map<String, StopArea> rawAreasById = new LinkedHashMap<>();
        for (StopArea stopArea : stopAreas) {
            rawAreasById.put(stopArea.areaId(), stopArea);
        }
        Map<String, StopAreaCity> citiesByAreaId = new LinkedHashMap<>();
        for (StopAreaCity city : stopAreaCities) {
            citiesByAreaId.put(city.areaId(), city);
        }
        CityPrefixAliasResolver prefixResolver = CityPrefixAliasResolver.build(stopAreas, stopAreaCities);

        Map<String, StopAreaDisplayName> displayNamesByAreaId = new LinkedHashMap<>();
        for (CanonicalStopArea area : canonicalAreas) {
            displayNamesByAreaId.put(
                    area.primaryStopAreaId(),
                    StopAreaPublicDisplayNameFormatter.forMember(
                            area,
                            area.primaryStopAreaId(),
                            citiesByAreaId.get(area.primaryStopAreaId()),
                            prefixResolver
                    )
            );
        }
        for (CanonicalStopAreaMember member : members) {
            CanonicalStopArea area = areasById.get(member.canonicalAreaId());
            if (area == null) {
                continue;
            }
            if (member.areaId().equals(area.primaryStopAreaId())) {
                continue;
            }
            StopArea rawArea = rawAreasById.get(member.areaId());
            if (rawArea == null) {
                continue;
            }
            displayNamesByAreaId.put(
                    member.areaId(),
                    StopAreaPublicDisplayNameFormatter.forFamilyMember(
                            rawArea,
                            area.canonicalAreaId(),
                            citiesByAreaId.get(rawArea.areaId()),
                            prefixResolver
                    )
            );
        }
        for (StopArea stopArea : stopAreas) {
            if (displayNamesByAreaId.containsKey(stopArea.areaId())) {
                continue;
            }
            displayNamesByAreaId.put(
                    stopArea.areaId(),
                    StopAreaPublicDisplayNameFormatter.forRawStopArea(
                            stopArea,
                            citiesByAreaId.get(stopArea.areaId()),
                            prefixResolver
                    )
            );
        }
        return List.copyOf(displayNamesByAreaId.values());
    }

    private static int addCanonicalFamilyAlias(
            PreparedStatement statement,
            int currentBatchSize,
            String areaId,
            String alias,
            int priority
    ) throws SQLException {
        String normalized = StopNameNormalizer.normalize(alias);
        if (areaId == null || areaId.isBlank() || normalized.isBlank()) {
            return currentBatchSize;
        }
        statement.setString(1, areaId);
        statement.setString(2, alias);
        statement.setString(3, normalized);
        statement.setString(4, "CANONICAL_FAMILY");
        statement.setString(5, "CANONICAL_STOP_AREA");
        statement.setInt(6, priority);
        statement.addBatch();
        return executeCanonicalBatchIfNeeded(
                statement,
                currentBatchSize,
                "canonical_stop_area_aliases"
        );
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private void commitStopTimesTransaction(PreparedStatement statement, BatchCounter counter, CommitStats commitStats) throws SQLException {
        long startedNanos = System.nanoTime();
        statement.executeBatch();
        counter.count = 0;
        commit();
        commitStats.add(Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000));
    }

    private static int executeBatchIfNeeded(PreparedStatement statement, int currentBatchSize) throws SQLException {
        int nextBatchSize = currentBatchSize + 1;
        if (nextBatchSize >= BATCH_SIZE) {
            statement.executeBatch();
            return 0;
        }
        return nextBatchSize;
    }

    private static int executeCanonicalBatchIfNeeded(
            PreparedStatement statement,
            int currentBatchSize,
            String phase
    ) throws SQLException {
        int nextBatchSize = executeBatchIfNeeded(statement, currentBatchSize);
        if (nextBatchSize == 0 && PerformanceTracker.usedMemoryMb() >= canonicalWriteHeapGuardThresholdMb()) {
            compactCanonicalWriteHeap(phase, statement);
        }
        return nextBatchSize;
    }

    private static long canonicalWriteHeapGuardThresholdMb() {
        long configured = Long.getLong("ixit.gtfs.canonicalWriteHeapGuardMb", -1L);
        if (configured > 0) {
            return configured;
        }
        long maximumHeapMb = Runtime.getRuntime().maxMemory() / (1024L * 1024L);
        return maximumHeapMb <= 2_700 ? 2_050 : 2_500;
    }

    private static void compactCanonicalWriteHeap(String phase, PreparedStatement statement) throws SQLException {
        statement.clearBatch();
        statement.clearParameters();
        long beforeMb = PerformanceTracker.usedMemoryMb();
        System.gc();
        long afterMb = PerformanceTracker.usedMemoryMb();
        System.err.println("[IXIT GTFS Preprocessor] section=heap_guard phase="
                + phase
                + " memory_used_mb="
                + beforeMb
                + " after_gc_mb="
                + afterMb);
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String exceptionAction(Integer exceptionType) {
        if (exceptionType == null) {
            return "UNKNOWN";
        }
        return switch (exceptionType) {
            case 1 -> "ADDITION";
            case 2 -> "REMOVAL";
            default -> "UNKNOWN";
        };
    }

    private String readFeedTimezones() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT COALESCE(group_concat(agency_timezone, ','), 'unknown')
                     FROM (
                         SELECT DISTINCT TRIM(agency_timezone) AS agency_timezone
                         FROM feed_agencies
                         WHERE agency_timezone IS NOT NULL AND TRIM(agency_timezone) <> ''
                         ORDER BY agency_timezone
                     )
                     """)) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    private static void setDouble(PreparedStatement statement, int index, Double value) throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else {
            statement.setDouble(index, value);
        }
    }

    private static boolean isParentStation(Stop stop) {
        return stop.parentStation() == null || stop.parentStation().isBlank();
    }

    private static int bool(boolean value) {
        return value ? 1 : 0;
    }

    private Path walPath() {
        return Path.of(databasePath.toString() + "-wal");
    }

    private static long fileSize(Path path) throws SQLException {
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException ex) {
            throw new SQLException("Failed to read SQLite file size: " + path, ex);
        }
    }

    private static String pragmaName(String propertyName, String defaultValue) {
        return validatePragmaName(System.getProperty(propertyName, defaultValue), propertyName);
    }

    private static String optionalPragmaName(String propertyName) {
        String value = System.getProperty(propertyName, "").trim();
        if (value.isBlank()) {
            return "";
        }
        return validatePragmaName(value, propertyName);
    }

    private static String optionalPragmaInteger(String propertyName) {
        String value = System.getProperty(propertyName, "").trim();
        if (value.isBlank()) {
            return "";
        }
        if (!value.matches("-?\\d+")) {
            throw new IllegalArgumentException("Invalid SQLite pragma integer for " + propertyName + ": " + value);
        }
        return value;
    }

    private static String validatePragmaName(String value, String propertyName) {
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid SQLite pragma value for " + propertyName + ": " + value);
        }
        return normalized;
    }

    private static String synchronousName(String value) {
        return switch (value) {
            case "0" -> "OFF";
            case "1" -> "NORMAL";
            case "2" -> "FULL";
            case "3" -> "EXTRA";
            default -> value;
        };
    }

    private static String tempStoreName(String value) {
        return switch (value) {
            case "0" -> "DEFAULT";
            case "1" -> "FILE";
            case "2" -> "MEMORY";
            default -> value;
        };
    }

    @FunctionalInterface
    public interface StopTimeObserver {
        void onStopTime(String tripId, String stopId, int stopSequence);
    }

    @FunctionalInterface
    public interface TransferObserver {
        void onTransfer(GtfsTransfer transfer);
    }

    private static final class BatchCounter {
        private int count;
    }

    private static final class RowCounter {
        private int count;
    }

    private static final class UnknownStopTracker {
        private long count;
        private final Set<String> samples = new LinkedHashSet<>();

        private void add(String stopId) {
            count++;
            if (samples.size() < 5) {
                samples.add(stopId);
            }
        }
    }

    private static final class CommitStats {
        private long count;
        private long totalMs;
        private long maxMs;

        private void add(long durationMs) {
            count++;
            totalMs += durationMs;
            maxMs = Math.max(maxMs, durationMs);
        }

        private long averageMs() {
            return count == 0 ? 0 : totalMs / count;
        }
    }

    public record StopTimesWriteReport(
            long rows,
            long unknownStopReferences,
            List<String> unknownStopSamples,
            long durationMs,
            long rowsPerSecond,
            int batchSize,
            int stopTimesCommitRows,
            long commitCount,
            long averageCommitMs,
            long maxCommitMs,
            long sqliteSizeAfterBytes,
            long walSizeAfterBytes,
            Map<String, String> sqlitePragmas
    ) {
    }

    private static final class GtfsWriteException extends RuntimeException {
        private final SQLException sqlException;

        private GtfsWriteException(SQLException sqlException) {
            super(sqlException);
            this.sqlException = sqlException;
        }
    }
}
