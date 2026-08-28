package de.ixit.gtfs;

import de.ixit.gtfs.GtfsStopIdentityResolver.RawStop;
import de.ixit.gtfs.GtfsStopIdentityResolver.StopKey;
import de.ixit.gtfs.GtfsTripFusionPlanner.Decision;
import de.ixit.gtfs.GtfsTripFusionPlanner.DecisionKind;
import de.ixit.gtfs.GtfsTripFusionPlanner.StopCall;
import de.ixit.gtfs.GtfsTripFusionPlanner.TripKey;
import de.ixit.gtfs.GtfsTripFusionPlanner.TripPattern;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class GtfsFeedFusion {
    private static final String ID_SEPARATOR = "::";
    private static final int MAX_COMPARISON_BUCKET_TRIPS = 1_000;
    private static final int MAX_REPORT_DIAGNOSTICS = 2_000;
    private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("[A-Z][A-Z0-9_]{1,31}");
    private static final List<String> OUTPUT_FILES = List.of(
            "agency.txt",
            "stops.txt",
            "routes.txt",
            "networks.txt",
            "route_networks.txt",
            "trips.txt",
            "stop_times.txt",
            "calendar.txt",
            "calendar_dates.txt",
            "transfers.txt",
            "frequencies.txt",
            "shapes.txt",
            "pathways.txt",
            "levels.txt",
            "location_groups.txt",
            "location_group_stops.txt",
            "booking_rules.txt",
            "areas.txt",
            "stop_areas.txt",
            "fare_attributes.txt",
            "fare_rules.txt",
            "attributions.txt",
            "translations.txt"
    );
    private static final Map<String, Set<String>> NAMESPACED_FIELDS = namespacedFields();
    private static final Map<String, Set<String>> STOP_REFERENCE_FIELDS = stopReferenceFields();
    private static final Set<String> TRIP_REFERENCE_FIELDS = Set.of("trip_id", "from_trip_id", "to_trip_id");

    public GtfsFeedFusionReport run(
            List<Source> requestedSources,
            Path outputZip,
            Path reportOutput
    ) throws IOException, SQLException {
        List<Source> sources = validateSources(requestedSources);
        Path normalizedOutput = Objects.requireNonNull(outputZip, "outputZip").toAbsolutePath().normalize();
        Path effectiveReport = reportOutput == null
                ? normalizedOutput.resolveSibling(stripExtension(normalizedOutput.getFileName().toString())
                + "-fusion-report.json")
                : reportOutput.toAbsolutePath().normalize();
        validateOutputPaths(sources, normalizedOutput, effectiveReport);
        Path outputParent = normalizedOutput.getParent();
        if (outputParent != null) {
            Files.createDirectories(outputParent);
        }
        String runId = UUID.randomUUID().toString();
        Path stagingDatabase = normalizedOutput.resolveSibling(
                normalizedOutput.getFileName() + ".fusion-" + runId + ".sqlite"
        );
        Path temporaryOutput = normalizedOutput.resolveSibling(
                normalizedOutput.getFileName() + ".fusion-" + runId + ".tmp"
        );

        List<RawStop> stops = new ArrayList<>();
        List<SourceData> sourceData = new ArrayList<>();
        try {
            createStagingDatabase(stagingDatabase);
            try (Connection connection = open(stagingDatabase)) {
                for (Source source : sources) {
                    logProgress("metadata", source.sourceId(), 0, "START");
                    sourceData.add(loadMetadata(source, stops, connection));
                    logProgress("metadata", source.sourceId(), sourceData.get(sourceData.size() - 1).tripCount, "READY");
                }
            }
            Map<StopKey, String> canonicalStops = new GtfsStopIdentityResolver().resolve(stops);
            long matchedStopIdentities = matchedStopIdentityCount(canonicalStops);
            StopOutputPlan stopOutputPlan = buildStopOutputPlan(stops, canonicalStops);
            logProgress("stop_families", "ALL", stopOutputPlan.suppressedStops().size(), "READY");
            stops.clear();
            logProgress("service_signatures", "ALL", 0, "START");
            buildServiceSignatures(stagingDatabase);
            logProgress("service_signatures", "ALL", 0, "READY");
            try (Connection connection = open(stagingDatabase)) {
                for (SourceData data : sourceData) {
                    logProgress("stop_times", data.source.sourceId(), 0, "START");
                    data.stopTimeCount = ingestStopTimes(connection, data.source, canonicalStops);
                    logProgress("stop_times", data.source.sourceId(), data.stopTimeCount, "READY");
                }
            }
            canonicalStops = null;
            logProgress("trip_patterns", "ALL", 0, "START");
            buildTripPatterns(stagingDatabase);
            logProgress("trip_patterns", "ALL", 0, "READY");
            FusionState fusionState = classify(stagingDatabase);
            writeOutput(sources, temporaryOutput, stagingDatabase, fusionState, stopOutputPlan);
            replaceAtomically(temporaryOutput, normalizedOutput);

            long inputTrips = sourceData.stream().mapToLong(data -> data.tripCount).sum();
            long outputTrips = inputTrips - fusionState.suppressedTrips.size();
            List<GtfsFeedFusionReport.Diagnostic> diagnostics = fusionState.decisions.stream()
                    .limit(MAX_REPORT_DIAGNOSTICS)
                    .map(decision -> new GtfsFeedFusionReport.Diagnostic(
                            decision.kind(),
                            decision.primary().namespacedId(),
                            decision.secondary().namespacedId(),
                            decision.reason()
                    ))
                    .toList();
            GtfsFeedFusionReport report = GtfsFeedFusionReport.from(
                    normalizedOutput,
                    sourceData.stream().map(SourceData::summary).toList(),
                    inputTrips,
                    outputTrips,
                    matchedStopIdentities,
                    fusionState.decisions,
                    diagnostics,
                    fusionState.decisions.size() > diagnostics.size()
            );
            report.writeJson(effectiveReport);
            return report;
        } finally {
            deleteStagingDatabase(stagingDatabase);
            Files.deleteIfExists(temporaryOutput);
        }
    }

    private static void validateOutputPaths(List<Source> sources, Path output, Path report) {
        if (output.equals(report)) {
            throw new IllegalArgumentException("Fusion ZIP and report output must use different paths");
        }
        for (Source source : sources) {
            Path input = source.inputZip().toAbsolutePath().normalize();
            if (input.equals(output) || input.equals(report)) {
                throw new IllegalArgumentException("Fusion output must not overwrite an input ZIP: " + input);
            }
        }
    }

    private static void replaceAtomically(Path temporaryOutput, Path output) throws IOException {
        try {
            Files.move(
                    temporaryOutput,
                    output,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temporaryOutput, output, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<Source> validateSources(List<Source> requestedSources) {
        if (requestedSources == null || requestedSources.size() < 2) {
            throw new IllegalArgumentException("GTFS fusion requires at least two sources");
        }
        Set<String> sourceIds = new LinkedHashSet<>();
        List<Source> sources = new ArrayList<>();
        for (int index = 0; index < requestedSources.size(); index++) {
            Source source = Objects.requireNonNull(requestedSources.get(index), "source");
            if (!SOURCE_ID_PATTERN.matcher(source.sourceId()).matches()) {
                throw new IllegalArgumentException("Invalid source ID: " + source.sourceId());
            }
            if (!sourceIds.add(source.sourceId())) {
                throw new IllegalArgumentException("Duplicate source ID: " + source.sourceId());
            }
            if (!Files.isRegularFile(source.inputZip())) {
                throw new IllegalArgumentException("GTFS ZIP does not exist: " + source.inputZip());
            }
            sources.add(new Source(source.sourceId(), source.inputZip().toAbsolutePath().normalize(), index));
        }
        return List.copyOf(sources);
    }

    private static SourceData loadMetadata(
            Source source,
            List<RawStop> allStops,
            Connection connection
    ) throws IOException, SQLException {
        SourceData data = new SourceData(source);
        try (GtfsZipReader zip = GtfsZipReader.open(source.inputZip());
             PreparedStatement routeInsert = connection.prepareStatement("""
                     INSERT INTO routes_meta(source_id, route_id, route_type, line_key)
                     VALUES (?, ?, ?, ?)
                     """);
             PreparedStatement tripInsert = connection.prepareStatement("""
                     INSERT INTO trips_meta(
                         source_id, trip_id, source_priority, route_id, service_id, journey_key
                     ) VALUES (?, ?, ?, ?, ?, ?)
                     """);
             PreparedStatement calendarInsert = connection.prepareStatement("""
                     INSERT OR REPLACE INTO calendar_definitions(
                         source_id, service_id, weekday_flags, start_date, end_date
                     ) VALUES (?, ?, ?, ?, ?)
                     """);
             PreparedStatement exceptionInsert = connection.prepareStatement("""
                     INSERT OR REPLACE INTO calendar_exceptions(
                         source_id, service_id, service_date, exception_type
                     ) VALUES (?, ?, ?, ?)
                     """)) {
            data.stopCount = GtfsCsvReader.read(zip.openRequired("stops.txt"), row -> {
                RawStop stop = new RawStop(
                        source.sourceId(),
                        source.priority(),
                        row.required("stop_id"),
                        row.optional("stop_code"),
                        row.optional("stop_name"),
                        row.optionalDouble("stop_lat"),
                        row.optionalDouble("stop_lon"),
                        row.optional("parent_station"),
                        row.optionalInt("location_type")
                );
                allStops.add(stop);
            });
            data.routeCount = readBatched(
                    connection, zip.openRequired("routes.txt"), routeInsert,
                    "routes", source.sourceId(), (insert, row) -> {
                String shortName = text(row.optional("route_short_name"));
                String longName = text(row.optional("route_long_name"));
                Integer routeType = row.optionalInt("route_type");
                insert.setString(1, source.sourceId());
                insert.setString(2, row.required("route_id"));
                insert.setInt(3, GtfsRouteTypeFamily.canonical(routeType));
                insert.setString(4, identityText(shortName.isBlank() ? longName : shortName));
            });
            data.tripCount = readBatched(
                    connection, zip.openRequired("trips.txt"), tripInsert,
                    "trips", source.sourceId(), (insert, row) -> {
                insert.setString(1, source.sourceId());
                insert.setString(2, row.required("trip_id"));
                insert.setInt(3, source.priority());
                insert.setString(4, row.required("route_id"));
                insert.setString(5, row.required("service_id"));
                insert.setString(6, identityText(row.optional("trip_short_name")));
            });
            if (zip.exists("calendar.txt")) {
                readBatched(
                        connection, zip.openRequired("calendar.txt"), calendarInsert,
                        "calendar", source.sourceId(), (insert, row) -> {
                    insert.setString(1, source.sourceId());
                    insert.setString(2, row.required("service_id"));
                    insert.setInt(3, weekdayFlags(row));
                    insert.setString(4, parseDate(row.required("start_date")).toString());
                    insert.setString(5, parseDate(row.required("end_date")).toString());
                });
            }
            if (zip.exists("calendar_dates.txt")) {
                readBatched(
                        connection, zip.openRequired("calendar_dates.txt"), exceptionInsert,
                        "calendar_dates", source.sourceId(), (insert, row) -> {
                    Integer exceptionType = row.optionalInt("exception_type");
                    insert.setString(1, source.sourceId());
                    insert.setString(2, row.required("service_id"));
                    insert.setString(3, parseDate(row.required("date")).toString());
                    insert.setInt(4, exceptionType == null ? 0 : exceptionType);
                });
            }
        }
        return data;
    }

    private static long readBatched(
            Connection connection,
            InputStream input,
            PreparedStatement insert,
            String stage,
            String sourceId,
            RowBinder binder
    ) throws IOException, SQLException {
        long[] count = {0L};
        connection.setAutoCommit(false);
        try {
            try {
                GtfsCsvReader.read(input, row -> {
                    try {
                        binder.bind(insert, row);
                        insert.addBatch();
                        count[0]++;
                        if (count[0] % 50_000 == 0) {
                            insert.executeBatch();
                            connection.commit();
                        }
                        if (count[0] % 500_000 == 0) {
                            logProgress(stage, sourceId, count[0], "RUNNING");
                        }
                    } catch (SQLException ex) {
                        throw new StagingException(ex);
                    }
                });
            } catch (StagingException ex) {
                throw ex.sqlException;
            }
            insert.executeBatch();
            connection.commit();
            return count[0];
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static long ingestStopTimes(
            Connection connection,
            Source source,
            Map<StopKey, String> canonicalStops
    ) throws IOException, SQLException {
        String insertSql = """
                INSERT INTO stop_calls(
                    source_id, trip_id, stop_sequence, arrival_seconds,
                    departure_seconds, stop_id, canonical_stop_key
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        long[] count = {0L};
        connection.setAutoCommit(false);
        try (PreparedStatement insert = connection.prepareStatement(insertSql);
             GtfsZipReader zip = GtfsZipReader.open(source.inputZip())) {
            try {
                GtfsCsvReader.read(zip.openRequired("stop_times.txt"), row -> {
                    try {
                        String stopId = row.required("stop_id");
                        insert.setString(1, source.sourceId());
                        insert.setString(2, row.required("trip_id"));
                        insert.setInt(3, parseInt(row.required("stop_sequence"), 0));
                        insert.setInt(4, parseTime(row.optional("arrival_time")));
                        insert.setInt(5, parseTime(row.optional("departure_time")));
                        insert.setString(6, stopId);
                        insert.setString(7, canonicalStops.getOrDefault(
                                new StopKey(source.sourceId(), stopId),
                                "S:" + namespace(source.sourceId(), stopId)
                        ));
                        insert.addBatch();
                        count[0]++;
                        if (count[0] % 50_000 == 0) {
                            insert.executeBatch();
                            connection.commit();
                        }
                        if (count[0] % 500_000 == 0) {
                            logProgress("stop_times", source.sourceId(), count[0], "RUNNING");
                        }
                    } catch (SQLException ex) {
                        throw new StagingException(ex);
                    }
                });
            } catch (StagingException ex) {
                throw ex.sqlException;
            }
            insert.executeBatch();
            connection.commit();
            return count[0];
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static void buildServiceSignatures(Path stagingDatabase) throws SQLException {
        String selectSql = """
                WITH services AS (
                    SELECT source_id, service_id FROM calendar_definitions
                    UNION
                    SELECT source_id, service_id FROM calendar_exceptions
                )
                SELECT s.source_id, s.service_id,
                       c.weekday_flags, c.start_date, c.end_date,
                       e.service_date, e.exception_type
                FROM services s
                LEFT JOIN calendar_definitions c
                  ON c.source_id=s.source_id AND c.service_id=s.service_id
                LEFT JOIN calendar_exceptions e
                  ON e.source_id=s.source_id AND e.service_id=s.service_id
                ORDER BY s.source_id, s.service_id, e.service_date
                """;
        String insertSql = """
                INSERT INTO service_signatures(source_id, service_id, service_signature)
                VALUES (?, ?, ?)
                """;
        try (Connection read = open(stagingDatabase);
             Connection write = open(stagingDatabase);
             Statement statement = read.createStatement();
             ResultSet rows = statement.executeQuery(selectSql);
             PreparedStatement insert = write.prepareStatement(insertSql)) {
            write.setAutoCommit(false);
            String currentSource = null;
            String currentService = null;
            CalendarDefinition definition = null;
            Map<LocalDate, Integer> exceptions = new HashMap<>();
            int pending = 0;
            while (rows.next()) {
                String sourceId = rows.getString(1);
                String serviceId = rows.getString(2);
                if (currentSource != null
                        && (!currentSource.equals(sourceId) || !currentService.equals(serviceId))) {
                    addServiceSignature(insert, currentSource, currentService, definition, exceptions);
                    pending++;
                    if (pending >= 10_000) {
                        insert.executeBatch();
                        write.commit();
                        pending = 0;
                    }
                    definition = null;
                    exceptions = new HashMap<>();
                }
                currentSource = sourceId;
                currentService = serviceId;
                String startDate = rows.getString(4);
                if (definition == null && startDate != null) {
                    definition = new CalendarDefinition(
                            rows.getInt(3), LocalDate.parse(startDate), LocalDate.parse(rows.getString(5))
                    );
                }
                String exceptionDate = rows.getString(6);
                if (exceptionDate != null) {
                    exceptions.put(LocalDate.parse(exceptionDate), rows.getInt(7));
                }
            }
            if (currentSource != null) {
                addServiceSignature(insert, currentSource, currentService, definition, exceptions);
                pending++;
            }
            if (pending > 0) {
                insert.executeBatch();
            }
            write.commit();
        }
    }

    private static void addServiceSignature(
            PreparedStatement insert,
            String sourceId,
            String serviceId,
            CalendarDefinition definition,
            Map<LocalDate, Integer> exceptions
    ) throws SQLException {
        insert.setString(1, sourceId);
        insert.setString(2, serviceId);
        insert.setString(3, calculateServiceSignature(sourceId, serviceId, definition, exceptions));
        insert.addBatch();
    }

    private static void buildTripPatterns(Path stagingDatabase) throws SQLException {
        String selectSql = """
                SELECT c.source_id, c.trip_id, c.stop_sequence, c.arrival_seconds,
                       c.departure_seconds, c.stop_id, c.canonical_stop_key,
                       t.source_priority, r.route_type, r.line_key, t.journey_key,
                       COALESCE(s.service_signature,
                           'UNSCOPED:' || t.source_id || '::' || t.service_id)
                FROM stop_calls c
                JOIN trips_meta t
                  ON t.source_id=c.source_id AND t.trip_id=c.trip_id
                JOIN routes_meta r
                  ON r.source_id=t.source_id AND r.route_id=t.route_id
                LEFT JOIN service_signatures s
                  ON s.source_id=t.source_id AND s.service_id=t.service_id
                ORDER BY c.source_id, c.trip_id, c.stop_sequence
                """;
        String insertSql = """
                INSERT INTO trip_patterns(
                    source_id, trip_id, source_priority, route_type, line_key,
                    journey_key, service_signature, exact_hash, stop_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (Connection read = open(stagingDatabase);
             Connection write = open(stagingDatabase);
             Statement statement = read.createStatement();
             ResultSet rows = statement.executeQuery(selectSql);
             PreparedStatement insert = write.prepareStatement(insertSql)) {
            write.setAutoCommit(false);
            TripKey currentKey = null;
            PatternMetadata metadata = null;
            List<StopCall> calls = new ArrayList<>();
            int pending = 0;
            while (rows.next()) {
                TripKey rowKey = new TripKey(rows.getString(1), rows.getString(2));
                if (currentKey != null && !currentKey.equals(rowKey)) {
                    if (insertPattern(insert, currentKey, calls, metadata)) {
                        pending++;
                    }
                    if (pending >= 10_000) {
                        insert.executeBatch();
                        write.commit();
                        pending = 0;
                    }
                    calls = new ArrayList<>();
                }
                currentKey = rowKey;
                metadata = new PatternMetadata(
                        rows.getInt(8), rows.getInt(9), rows.getString(10),
                        rows.getString(11), rows.getString(12)
                );
                calls.add(new StopCall(
                        rowKey.sourceId(),
                        rows.getString(6),
                        rows.getString(7),
                        rows.getInt(4),
                        rows.getInt(5)
                ));
            }
            if (currentKey != null && insertPattern(insert, currentKey, calls, metadata)) {
                pending++;
            }
            if (pending > 0) {
                insert.executeBatch();
            }
            write.commit();
        }
    }

    private static boolean insertPattern(
            PreparedStatement insert,
            TripKey key,
            List<StopCall> calls,
            PatternMetadata metadata
    ) throws SQLException {
        if (calls.size() < 2 || metadata == null) {
            return false;
        }
        TripPattern pattern = metadata.toPattern(key, calls);
        insert.setString(1, key.sourceId());
        insert.setString(2, key.tripId());
        insert.setInt(3, metadata.sourcePriority());
        insert.setInt(4, metadata.routeType());
        insert.setString(5, metadata.lineKey());
        insert.setString(6, metadata.journeyKey());
        insert.setString(7, metadata.serviceSignature());
        insert.setString(8, sha256(pattern.exactKey()));
        insert.setInt(9, calls.size());
        insert.addBatch();
        return true;
    }

    private static FusionState classify(Path stagingDatabase) throws SQLException {
        FusionState state = new FusionState();
        try (Connection connection = open(stagingDatabase)) {
            classifyExactDuplicates(connection, state);
            List<ComparisonBucket> buckets = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("""
                         SELECT service_signature, route_type, line_key, COUNT(*)
                         FROM trip_patterns
                         GROUP BY service_signature, route_type, line_key
                         HAVING COUNT(DISTINCT source_id) > 1
                         ORDER BY service_signature, route_type, line_key
                         """)) {
                while (rows.next()) {
                    buckets.add(new ComparisonBucket(
                            rows.getString(1), rows.getInt(2), rows.getString(3), rows.getInt(4)
                    ));
                }
            }
            for (ComparisonBucket bucket : buckets) {
                List<TripPattern> patterns = loadBucketPatterns(connection, bucket).stream()
                        .filter(pattern -> !state.suppressedTrips.contains(pattern.key()))
                        .toList();
                if (patterns.size() < 2) {
                    continue;
                }
                if (patterns.size() > MAX_COMPARISON_BUCKET_TRIPS) {
                    TripKey bucketKey = new TripKey("FUSION", "BUCKET_" + sha256(bucket.toString()).substring(0, 16));
                    state.decisions.add(new Decision(
                            DecisionKind.AMBIGUOUS_KEPT,
                            bucketKey,
                            bucketKey,
                            List.of(),
                            "comparison bucket kept because it contains " + patterns.size()
                                    + " trips, above safety limit " + MAX_COMPARISON_BUCKET_TRIPS
                    ));
                    continue;
                }
                GtfsTripFusionPlanner.Plan plan = new GtfsTripFusionPlanner().plan(patterns);
                for (Decision decision : plan.decisions()) {
                    if (decision.kind() != DecisionKind.AMBIGUOUS_KEPT
                            && state.suppressedTrips.contains(decision.secondary())) {
                        continue;
                    }
                    state.decisions.add(decision);
                    if (decision.kind() != DecisionKind.AMBIGUOUS_KEPT) {
                        state.suppressedTrips.add(decision.secondary());
                        state.canonicalByTrip.put(decision.secondary(), decision.primary());
                    }
                    if (decision.kind() == DecisionKind.STITCHED) {
                        state.fusedCalls.put(decision.primary(), decision.fusedCalls());
                    }
                }
            }
        }
        return state;
    }

    private static void classifyExactDuplicates(Connection connection, FusionState state) throws SQLException {
        String sql = """
                SELECT exact_hash, source_id, trip_id, source_priority
                FROM trip_patterns
                ORDER BY exact_hash, source_priority, source_id, trip_id
                """;
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            String currentHash = null;
            List<PatternIdentity> group = new ArrayList<>();
            while (rows.next()) {
                String hash = rows.getString(1);
                if (currentHash != null && !currentHash.equals(hash)) {
                    suppressExactGroup(group, state);
                    group = new ArrayList<>();
                }
                currentHash = hash;
                group.add(new PatternIdentity(
                        new TripKey(rows.getString(2), rows.getString(3)),
                        rows.getInt(4)
                ));
            }
            suppressExactGroup(group, state);
        }
    }

    private static void suppressExactGroup(List<PatternIdentity> group, FusionState state) {
        if (group.size() < 2 || group.stream().map(value -> value.key().sourceId()).distinct().count() < 2) {
            return;
        }
        PatternIdentity primary = group.stream()
                .min(Comparator.comparingInt(PatternIdentity::priority)
                        .thenComparing(value -> value.key().namespacedId()))
                .orElseThrow();
        for (PatternIdentity secondary : group) {
            if (secondary.key().equals(primary.key())
                    || secondary.key().sourceId().equals(primary.key().sourceId())) {
                continue;
            }
            state.suppressedTrips.add(secondary.key());
            state.canonicalByTrip.put(secondary.key(), primary.key());
            state.decisions.add(new Decision(
                    DecisionKind.EXACT_DUPLICATE,
                    primary.key(),
                    secondary.key(),
                    List.of(),
                    "same service, mode, line, stop sequence and times"
            ));
        }
    }

    private static List<TripPattern> loadBucketPatterns(
            Connection connection,
            ComparisonBucket bucket
    ) throws SQLException {
        String sql = """
                SELECT p.source_id, p.trip_id, p.source_priority, p.route_type,
                       p.line_key, p.journey_key, p.service_signature,
                       c.stop_sequence, c.arrival_seconds, c.departure_seconds,
                       c.stop_id, c.canonical_stop_key
                FROM trip_patterns p
                JOIN stop_calls c ON c.source_id=p.source_id AND c.trip_id=p.trip_id
                WHERE p.service_signature=? AND p.route_type=? AND p.line_key=?
                ORDER BY p.source_priority, p.source_id, p.trip_id, c.stop_sequence
                """;
        List<TripPattern> patterns = new ArrayList<>();
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, bucket.serviceSignature());
            query.setInt(2, bucket.routeType());
            query.setString(3, bucket.lineKey());
            try (ResultSet rows = query.executeQuery()) {
                TripKey currentKey = null;
                PatternMetadata metadata = null;
                List<StopCall> calls = new ArrayList<>();
                while (rows.next()) {
                    TripKey rowKey = new TripKey(rows.getString(1), rows.getString(2));
                    if (currentKey != null && !currentKey.equals(rowKey)) {
                        patterns.add(metadata.toPattern(currentKey, calls));
                        calls = new ArrayList<>();
                    }
                    currentKey = rowKey;
                    metadata = new PatternMetadata(
                            rows.getInt(3), rows.getInt(4), rows.getString(5),
                            rows.getString(6), rows.getString(7)
                    );
                    calls.add(new StopCall(
                            rowKey.sourceId(), rows.getString(11), rows.getString(12),
                            rows.getInt(9), rows.getInt(10)
                    ));
                }
                if (currentKey != null) {
                    patterns.add(metadata.toPattern(currentKey, calls));
                }
            }
        }
        return patterns;
    }

    private static void writeOutput(
            List<Source> sources,
            Path output,
            Path stagingDatabase,
            FusionState fusionState,
            StopOutputPlan stopOutputPlan
    ) throws IOException, SQLException {
        try (ZipOutputStream zipOutput = new ZipOutputStream(Files.newOutputStream(output))) {
            for (String fileName : OUTPUT_FILES) {
                writeMergedFile(zipOutput, fileName, sources, fusionState, stopOutputPlan);
            }
            writeFeedInfo(zipOutput, sources);
            writeSourceProvenance(zipOutput, sources);
            writeStopMappings(zipOutput, stopOutputPlan);
            writeTripMappings(zipOutput, stagingDatabase, fusionState);
        }
    }

    private static void writeMergedFile(
            ZipOutputStream zipOutput,
            String fileName,
            List<Source> sources,
            FusionState fusionState,
            StopOutputPlan stopOutputPlan
    ) throws IOException {
        List<String> headers = mergedHeaders(sources, fileName);
        if (headers.isEmpty()) {
            return;
        }
        zipOutput.putNextEntry(new ZipEntry(fileName));
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(zipOutput, StandardCharsets.UTF_8),
                1024 * 1024
        );
        GtfsCsvWriter.writeRow(writer, headers);
        Set<TripKey> writtenOverrides = new HashSet<>();
        Set<String> writtenTranslations = new HashSet<>();
        try {
            for (Source source : sources) {
                try (GtfsZipReader input = GtfsZipReader.open(source.inputZip())) {
                    if (!input.exists(fileName)) {
                        continue;
                    }
                    GtfsCsvReader.read(input.openRequired(fileName), row -> {
                        try {
                            if (fileName.equals("stops.txt")
                                    && stopOutputPlan.isSuppressed(source.sourceId(), row.required("stop_id"))) {
                                return;
                            }
                            TripKey rowTrip = tripKeyForRow(fileName, source.sourceId(), row);
                            if (rowTrip != null && fusionState.suppressedTrips.contains(rowTrip)
                                    && (fileName.equals("trips.txt")
                                    || fileName.equals("stop_times.txt")
                                    || fileName.equals("frequencies.txt"))) {
                                return;
                            }
                            if (fileName.equals("stop_times.txt") && rowTrip != null
                                    && fusionState.fusedCalls.containsKey(rowTrip)) {
                                if (writtenOverrides.add(rowTrip)) {
                                    writeFusedStopTimes(
                                            writer,
                                            headers,
                                            rowTrip,
                                            fusionState.fusedCalls.get(rowTrip),
                                            stopOutputPlan
                                    );
                                }
                                return;
                            }
                            List<String> values = new ArrayList<>(headers.size());
                            for (String header : headers) {
                                String value = text(row.optional(header));
                                if (fileName.equals("trips.txt") && header.equals("shape_id")
                                        && rowTrip != null && fusionState.fusedCalls.containsKey(rowTrip)) {
                                    value = "";
                                } else if (fileName.equals("translations.txt")
                                        && header.equals("record_id") && !value.isBlank()) {
                                    value = translationRecordId(
                                            source.sourceId(), row.optional("table_name"), value,
                                            fusionState, stopOutputPlan
                                    );
                                } else if (header.equals("agency_id") && value.isBlank()
                                        && (fileName.equals("agency.txt") || fileName.equals("routes.txt"))) {
                                    value = namespace(source.sourceId(), "__DEFAULT_AGENCY");
                                } else if (TRIP_REFERENCE_FIELDS.contains(header) && !value.isBlank()) {
                                    value = canonicalTripReference(source.sourceId(), value, fusionState);
                                } else if (STOP_REFERENCE_FIELDS.getOrDefault(fileName, Set.of()).contains(header)
                                        && !value.isBlank()) {
                                    value = stopOutputPlan.outputId(source.sourceId(), value);
                                } else if (NAMESPACED_FIELDS.getOrDefault(fileName, Set.of()).contains(header)
                                        && !value.isBlank()) {
                                    value = namespace(source.sourceId(), value);
                                }
                                values.add(value);
                            }
                            if (fileName.equals("translations.txt")
                                    && !writtenTranslations.add(translationKey(headers, values))) {
                                return;
                            }
                            GtfsCsvWriter.writeRow(writer, values);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
                } catch (UncheckedIOException ex) {
                    throw ex.getCause();
                }
            }
            writer.flush();
        } finally {
            zipOutput.closeEntry();
        }
    }

    private static void writeFusedStopTimes(
            BufferedWriter writer,
            List<String> headers,
            TripKey primary,
            List<StopCall> calls,
            StopOutputPlan stopOutputPlan
    ) throws IOException {
        for (int index = 0; index < calls.size(); index++) {
            StopCall call = calls.get(index);
            List<String> values = new ArrayList<>(headers.size());
            for (String header : headers) {
                values.add(switch (header) {
                    case "trip_id" -> primary.namespacedId();
                    case "arrival_time" -> formatTime(call.arrivalSeconds());
                    case "departure_time" -> formatTime(call.departureSeconds());
                    case "stop_id" -> stopOutputPlan.outputId(call.sourceId(), call.stopId());
                    case "stop_sequence" -> Integer.toString(index + 1);
                    default -> "";
                });
            }
            GtfsCsvWriter.writeRow(writer, values);
        }
    }

    private static void writeFeedInfo(ZipOutputStream zipOutput, List<Source> sources) throws IOException {
        zipOutput.putNextEntry(new ZipEntry("feed_info.txt"));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipOutput, StandardCharsets.UTF_8));
        GtfsCsvWriter.writeRow(writer, List.of(
                "feed_publisher_name", "feed_publisher_url", "feed_lang", "feed_version"
        ));
        GtfsCsvWriter.writeRow(writer, List.of(
                "IXIT GTFS Fusion",
                "https://ixit.org",
                "mul",
                sources.stream().map(Source::sourceId).reduce((left, right) -> left + "+" + right).orElse("")
        ));
        writer.flush();
        zipOutput.closeEntry();
    }

    private static void writeSourceProvenance(ZipOutputStream zipOutput, List<Source> sources) throws IOException {
        zipOutput.putNextEntry(new ZipEntry("ixit_fusion_sources.txt"));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipOutput, StandardCharsets.UTF_8));
        GtfsCsvWriter.writeRow(writer, List.of("source_id", "source_priority", "input_file"));
        for (Source source : sources) {
            GtfsCsvWriter.writeRow(writer, List.of(
                    source.sourceId(), Integer.toString(source.priority()), source.inputZip().toString()
            ));
        }
        writer.flush();
        zipOutput.closeEntry();
    }

    private static void writeStopMappings(
            ZipOutputStream zipOutput,
            StopOutputPlan stopOutputPlan
    ) throws IOException {
        zipOutput.putNextEntry(new ZipEntry("ixit_fusion_stop_mappings.txt"));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipOutput, StandardCharsets.UTF_8));
        GtfsCsvWriter.writeRow(writer, List.of(
                "canonical_stop_id", "source_id", "source_stop_id", "match_method"
        ));
        List<Map.Entry<StopKey, String>> mappings = stopOutputPlan.outputIds().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.comparing(StopKey::namespacedId)))
                .toList();
        for (Map.Entry<StopKey, String> mapping : mappings) {
            StopKey sourceStop = mapping.getKey();
            GtfsCsvWriter.writeRow(writer, List.of(
                    mapping.getValue(),
                    sourceStop.sourceId(),
                    sourceStop.stopId(),
                    mapping.getValue().equals(sourceStop.namespacedId()) ? "PRIMARY" : "CROSS_SOURCE_IDENTITY"
            ));
        }
        writer.flush();
        zipOutput.closeEntry();
    }

    private static void writeTripMappings(
            ZipOutputStream zipOutput,
            Path stagingDatabase,
            FusionState fusionState
    ) throws IOException, SQLException {
        Map<TripKey, DecisionKind> methodBySecondary = new HashMap<>();
        fusionState.decisions.stream()
                .filter(decision -> decision.kind() != DecisionKind.AMBIGUOUS_KEPT)
                .forEach(decision -> methodBySecondary.put(decision.secondary(), decision.kind()));
        zipOutput.putNextEntry(new ZipEntry("ixit_fusion_trip_mappings.txt"));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zipOutput, StandardCharsets.UTF_8));
        GtfsCsvWriter.writeRow(writer, List.of(
                "canonical_trip_id", "source_id", "source_trip_id", "match_method"
        ));
        try (Connection connection = open(stagingDatabase);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT source_id, trip_id
                     FROM trips_meta
                     ORDER BY source_id, trip_id
                     """)) {
            while (rows.next()) {
                TripKey key = new TripKey(rows.getString(1), rows.getString(2));
                TripKey canonical = fusionState.canonicalByTrip.getOrDefault(key, key);
                GtfsCsvWriter.writeRow(writer, List.of(
                        canonical.namespacedId(),
                        key.sourceId(),
                        key.tripId(),
                        methodBySecondary.getOrDefault(key, DecisionKind.AMBIGUOUS_KEPT)
                                == DecisionKind.AMBIGUOUS_KEPT
                                ? "PRIMARY" : methodBySecondary.get(key).name()
                ));
            }
        }
        writer.flush();
        zipOutput.closeEntry();
    }

    private static List<String> mergedHeaders(List<Source> sources, String fileName) throws IOException {
        Set<String> headers = new LinkedHashSet<>();
        for (Source source : sources) {
            try (GtfsZipReader zip = GtfsZipReader.open(source.inputZip())) {
                if (zip.exists(fileName)) {
                    headers.addAll(GtfsCsvReader.readHeaders(zip.openRequired(fileName)));
                }
            }
        }
        return List.copyOf(headers);
    }

    private static TripKey tripKeyForRow(String fileName, String sourceId, GtfsCsvReader.Row row) {
        if (!fileName.equals("trips.txt") && !fileName.equals("stop_times.txt")
                && !fileName.equals("frequencies.txt")) {
            return null;
        }
        String tripId = row.optional("trip_id");
        return tripId == null || tripId.isBlank() ? null : new TripKey(sourceId, tripId);
    }

    private static String canonicalTripReference(
            String sourceId,
            String rawTripId,
            FusionState fusionState
    ) {
        TripKey raw = new TripKey(sourceId, rawTripId);
        return fusionState.canonicalByTrip.getOrDefault(raw, raw).namespacedId();
    }

    private static String translationRecordId(
            String sourceId,
            String tableName,
            String rawRecordId,
            FusionState fusionState,
            StopOutputPlan stopOutputPlan
    ) {
        String normalizedTable = text(tableName).toLowerCase(Locale.ROOT).replace(".txt", "");
        if (normalizedTable.equals("trips") || normalizedTable.equals("stop_times")) {
            return canonicalTripReference(sourceId, rawRecordId, fusionState);
        }
        if (normalizedTable.equals("feed_info")) {
            return rawRecordId;
        }
        if (normalizedTable.equals("stops")) {
            return stopOutputPlan.outputId(sourceId, rawRecordId);
        }
        return namespace(sourceId, rawRecordId);
    }

    private static String translationKey(List<String> headers, List<String> values) {
        StringBuilder key = new StringBuilder();
        for (String field : List.of(
                "table_name", "field_name", "language", "record_id", "record_sub_id", "field_value"
        )) {
            int index = headers.indexOf(field);
            if (index >= 0) {
                key.append(values.get(index));
            }
            key.append('\u0000');
        }
        return key.toString();
    }

    private static void createStagingDatabase(Path database) throws SQLException {
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("""
                    CREATE TABLE routes_meta(
                        source_id TEXT NOT NULL,
                        route_id TEXT NOT NULL,
                        route_type INTEGER NOT NULL,
                        line_key TEXT NOT NULL,
                        PRIMARY KEY(source_id, route_id)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE trips_meta(
                        source_id TEXT NOT NULL,
                        trip_id TEXT NOT NULL,
                        source_priority INTEGER NOT NULL,
                        route_id TEXT NOT NULL,
                        service_id TEXT NOT NULL,
                        journey_key TEXT NOT NULL,
                        PRIMARY KEY(source_id, trip_id)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE calendar_definitions(
                        source_id TEXT NOT NULL,
                        service_id TEXT NOT NULL,
                        weekday_flags INTEGER NOT NULL,
                        start_date TEXT NOT NULL,
                        end_date TEXT NOT NULL,
                        PRIMARY KEY(source_id, service_id)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE calendar_exceptions(
                        source_id TEXT NOT NULL,
                        service_id TEXT NOT NULL,
                        service_date TEXT NOT NULL,
                        exception_type INTEGER NOT NULL,
                        PRIMARY KEY(source_id, service_id, service_date)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE service_signatures(
                        source_id TEXT NOT NULL,
                        service_id TEXT NOT NULL,
                        service_signature TEXT NOT NULL,
                        PRIMARY KEY(source_id, service_id)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE stop_calls(
                        source_id TEXT NOT NULL,
                        trip_id TEXT NOT NULL,
                        stop_sequence INTEGER NOT NULL,
                        arrival_seconds INTEGER NOT NULL,
                        departure_seconds INTEGER NOT NULL,
                        stop_id TEXT NOT NULL,
                        canonical_stop_key TEXT NOT NULL,
                        PRIMARY KEY(source_id, trip_id, stop_sequence)
                    ) WITHOUT ROWID
                    """);
            statement.execute("""
                    CREATE TABLE trip_patterns(
                        source_id TEXT NOT NULL,
                        trip_id TEXT NOT NULL,
                        source_priority INTEGER NOT NULL,
                        route_type INTEGER NOT NULL,
                        line_key TEXT NOT NULL,
                        journey_key TEXT NOT NULL,
                        service_signature TEXT NOT NULL,
                        exact_hash TEXT NOT NULL,
                        stop_count INTEGER NOT NULL,
                        PRIMARY KEY(source_id, trip_id)
                    ) WITHOUT ROWID
                    """);
            statement.execute("CREATE INDEX idx_fusion_calls_trip ON stop_calls(source_id, trip_id, stop_sequence)");
            statement.execute("CREATE INDEX idx_fusion_trips_route ON trips_meta(source_id, route_id)");
            statement.execute("CREATE INDEX idx_fusion_trips_service ON trips_meta(source_id, service_id)");
            statement.execute("CREATE INDEX idx_fusion_pattern_exact ON trip_patterns(exact_hash, source_priority)");
            statement.execute("CREATE INDEX idx_fusion_pattern_bucket ON trip_patterns(service_signature, route_type, line_key)");
        }
    }

    private static Connection open(Path database) throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath().normalize());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA synchronous=OFF");
            statement.execute("PRAGMA temp_store=FILE");
            statement.execute("PRAGMA cache_size=-32768");
        }
        return connection;
    }

    private static void deleteStagingDatabase(Path database) {
        for (String suffix : List.of("", "-wal", "-shm")) {
            try {
                Files.deleteIfExists(Path.of(database + suffix));
            } catch (IOException ignored) {
                // A failed cleanup must not hide the fusion result or its diagnostics.
            }
        }
    }

    private static long matchedStopIdentityCount(Map<StopKey, String> canonicalStops) {
        Map<String, Set<String>> sourcesByIdentity = new HashMap<>();
        canonicalStops.forEach((key, identity) -> sourcesByIdentity
                .computeIfAbsent(identity, ignored -> new LinkedHashSet<>())
                .add(key.sourceId()));
        return sourcesByIdentity.values().stream().filter(sourceIds -> sourceIds.size() > 1).count();
    }

    private static StopOutputPlan buildStopOutputPlan(
            List<RawStop> stops,
            Map<StopKey, String> canonicalStops
    ) {
        Map<StopKey, RawStop> stopsByKey = new LinkedHashMap<>();
        stops.forEach(stop -> stopsByKey.put(stop.key(), stop));

        Map<String, Map<StopKey, RawStop>> anchorsByIdentity = new LinkedHashMap<>();
        for (RawStop stop : stops) {
            StopKey anchorKey = outputAnchor(stop, stopsByKey);
            RawStop anchor = stopsByKey.get(anchorKey);
            String identity = canonicalStops.get(stop.key());
            if (anchor != null && identity != null) {
                anchorsByIdentity
                        .computeIfAbsent(identity, ignored -> new LinkedHashMap<>())
                        .putIfAbsent(anchorKey, anchor);
            }
        }

        Comparator<RawStop> preference = Comparator.comparingInt(RawStop::sourcePriority)
                .thenComparing(RawStop::sourceId)
                .thenComparing(RawStop::stopId);
        Map<StopKey, String> outputIds = new LinkedHashMap<>();
        Set<StopKey> suppressedStops = new LinkedHashSet<>();
        for (Map<StopKey, RawStop> groupedAnchors : anchorsByIdentity.values()) {
            List<RawStop> anchors = groupedAnchors.values().stream().sorted(preference).toList();
            if (anchors.stream().map(RawStop::sourceId).distinct().count() < 2) {
                continue;
            }
            RawStop primary = anchors.get(0);
            String canonicalOutputId = namespace(primary.sourceId(), primary.stopId());
            for (RawStop anchor : anchors) {
                outputIds.put(anchor.key(), canonicalOutputId);
                if (!anchor.key().equals(primary.key())) {
                    suppressedStops.add(anchor.key());
                }
            }
        }
        return new StopOutputPlan(Map.copyOf(outputIds), Set.copyOf(suppressedStops));
    }

    private static StopKey outputAnchor(RawStop stop, Map<StopKey, RawStop> stopsByKey) {
        RawStop current = stop;
        Set<StopKey> visited = new LinkedHashSet<>();
        while (!current.parentStation().isBlank()) {
            if (!visited.add(current.key())) {
                return stop.key();
            }
            RawStop parent = stopsByKey.get(new StopKey(current.sourceId(), current.parentStation()));
            if (parent == null) {
                return current.key();
            }
            current = parent;
        }
        return current.key();
    }

    private static void logProgress(String stage, String sourceId, long rows, String state) {
        System.out.println("[IXIT GTFS Fusion] stage=" + stage
                + " source=" + sourceId
                + " state=" + state
                + (rows > 0 ? " rows=" + rows : ""));
    }

    private static int parseTime(String value) {
        if (value == null || value.isBlank()) {
            return -1;
        }
        return GtfsTimeParser.toSecondsSinceServiceDayStart(value);
    }

    private static String formatTime(int seconds) {
        if (seconds < 0) {
            return "";
        }
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        int remainingSeconds = seconds % 60;
        return String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, remainingSeconds);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String identityText(String value) {
        return StopNameNormalizer.normalize(text(value));
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String namespace(String sourceId, String rawId) {
        return sourceId + ID_SEPARATOR + rawId;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte part : hash) {
                result.append(String.format(Locale.ROOT, "%02x", part));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static Map<String, Set<String>> namespacedFields() {
        Map<String, Set<String>> fields = new HashMap<>();
        fields.put("agency.txt", Set.of("agency_id"));
        fields.put("stops.txt", Set.of("stop_id", "parent_station", "zone_id", "level_id"));
        fields.put("routes.txt", Set.of("route_id", "agency_id", "network_id"));
        fields.put("networks.txt", Set.of("network_id"));
        fields.put("route_networks.txt", Set.of("route_id", "network_id"));
        fields.put("trips.txt", Set.of("route_id", "service_id", "block_id", "shape_id"));
        fields.put("stop_times.txt", Set.of("stop_id", "location_group_id", "location_id", "booking_rule_id"));
        fields.put("calendar.txt", Set.of("service_id"));
        fields.put("calendar_dates.txt", Set.of("service_id"));
        fields.put("transfers.txt", Set.of("from_stop_id", "to_stop_id", "from_route_id", "to_route_id"));
        fields.put("shapes.txt", Set.of("shape_id"));
        fields.put("pathways.txt", Set.of("pathway_id", "from_stop_id", "to_stop_id"));
        fields.put("levels.txt", Set.of("level_id"));
        fields.put("location_groups.txt", Set.of("location_group_id"));
        fields.put("location_group_stops.txt", Set.of("location_group_id", "stop_id"));
        fields.put("booking_rules.txt", Set.of("booking_rule_id"));
        fields.put("areas.txt", Set.of("area_id"));
        fields.put("stop_areas.txt", Set.of("area_id", "stop_id"));
        fields.put("fare_attributes.txt", Set.of("fare_id", "agency_id"));
        fields.put("fare_rules.txt", Set.of("fare_id", "route_id", "origin_id", "destination_id", "contains_id"));
        fields.put("attributions.txt", Set.of("attribution_id", "agency_id", "route_id"));
        return Map.copyOf(fields);
    }

    private static Map<String, Set<String>> stopReferenceFields() {
        Map<String, Set<String>> fields = new HashMap<>();
        fields.put("stops.txt", Set.of("stop_id", "parent_station"));
        fields.put("stop_times.txt", Set.of("stop_id"));
        fields.put("transfers.txt", Set.of("from_stop_id", "to_stop_id"));
        fields.put("pathways.txt", Set.of("from_stop_id", "to_stop_id"));
        fields.put("location_group_stops.txt", Set.of("stop_id"));
        fields.put("stop_areas.txt", Set.of("stop_id"));
        return Map.copyOf(fields);
    }

    public record Source(String sourceId, Path inputZip, int priority) {
        public Source(String sourceId, Path inputZip) {
            this(sourceId, inputZip, 0);
        }

        public Source {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(inputZip, "inputZip");
        }
    }

    private static final class SourceData {
        private final Source source;
        private long stopCount;
        private long routeCount;
        private long tripCount;
        private long stopTimeCount;

        private SourceData(Source source) {
            this.source = source;
        }

        private GtfsFeedFusionReport.SourceSummary summary() {
            return new GtfsFeedFusionReport.SourceSummary(
                    source.sourceId(), source.priority(), source.inputZip().toString(),
                    stopCount, routeCount, tripCount, stopTimeCount
            );
        }
    }

    private static String calculateServiceSignature(
            String sourceId,
            String serviceId,
            CalendarDefinition definition,
            Map<LocalDate, Integer> exceptions
    ) {
        if (definition == null && exceptions.isEmpty()) {
            return "UNSCOPED:" + namespace(sourceId, serviceId);
        }
        LocalDate start = definition == null ? null : definition.startDate();
        LocalDate end = definition == null ? null : definition.endDate();
        for (LocalDate date : exceptions.keySet()) {
            start = start == null || date.isBefore(start) ? date : start;
            end = end == null || date.isAfter(end) ? date : end;
        }
        if (start == null || end == null || end.toEpochDay() - start.toEpochDay() > 2_000) {
            return "UNSCOPED:" + namespace(sourceId, serviceId);
        }
        StringBuilder activeDates = new StringBuilder();
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            boolean active = definition != null && definition.activeOn(date.getDayOfWeek());
            Integer exception = exceptions.get(date);
            if (exception != null) {
                active = exception == 1;
            }
            if (active) {
                activeDates.append(date).append(';');
            }
        }
        if (activeDates.isEmpty()) {
            return "EMPTY:" + namespace(sourceId, serviceId);
        }
        return "DATES:" + sha256(activeDates.toString());
    }

    private static int weekdayFlags(GtfsCsvReader.Row row) {
        int flags = 0;
        String[] names = {"monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"};
        for (int index = 0; index < names.length; index++) {
            if (Integer.valueOf(1).equals(row.optionalInt(names[index]))) {
                flags |= 1 << index;
            }
        }
        return flags;
    }

    private static LocalDate parseDate(String value) {
        if (value.contains("-")) {
            return LocalDate.parse(value);
        }
        return LocalDate.of(
                Integer.parseInt(value.substring(0, 4)),
                Integer.parseInt(value.substring(4, 6)),
                Integer.parseInt(value.substring(6, 8))
        );
    }

    private record CalendarDefinition(int weekdayFlags, LocalDate startDate, LocalDate endDate) {
        private boolean activeOn(DayOfWeek dayOfWeek) {
            return (weekdayFlags & (1 << (dayOfWeek.getValue() - 1))) != 0;
        }
    }

    private static final class FusionState {
        private final Set<TripKey> suppressedTrips = new LinkedHashSet<>();
        private final Map<TripKey, TripKey> canonicalByTrip = new LinkedHashMap<>();
        private final Map<TripKey, List<StopCall>> fusedCalls = new LinkedHashMap<>();
        private final List<Decision> decisions = new ArrayList<>();
    }

    private record StopOutputPlan(
            Map<StopKey, String> outputIds,
            Set<StopKey> suppressedStops
    ) {
        private String outputId(String sourceId, String rawStopId) {
            StopKey key = new StopKey(sourceId, rawStopId);
            return outputIds.getOrDefault(key, key.namespacedId());
        }

        private boolean isSuppressed(String sourceId, String rawStopId) {
            return suppressedStops.contains(new StopKey(sourceId, rawStopId));
        }
    }

    private record PatternIdentity(TripKey key, int priority) {
    }

    private record ComparisonBucket(String serviceSignature, int routeType, String lineKey, int tripCount) {
    }

    private record PatternMetadata(
            int sourcePriority,
            int routeType,
            String lineKey,
            String journeyKey,
            String serviceSignature
    ) {
        private TripPattern toPattern(TripKey key, List<StopCall> calls) {
            return new TripPattern(
                    key, sourcePriority, routeType, lineKey, journeyKey, serviceSignature, calls
            );
        }
    }

    @FunctionalInterface
    private interface RowBinder {
        void bind(PreparedStatement insert, GtfsCsvReader.Row row) throws SQLException;
    }

    private static final class StagingException extends RuntimeException {
        private final SQLException sqlException;

        private StagingException(SQLException sqlException) {
            super(sqlException);
            this.sqlException = sqlException;
        }
    }
}
