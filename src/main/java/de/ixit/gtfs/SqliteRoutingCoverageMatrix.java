package de.ixit.gtfs;

import de.ixit.gtfs.TransitDataAccess.DirectTransitLegData;
import de.ixit.gtfs.TransitDataAccess.NextTransitLegData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SqliteRoutingCoverageMatrix {
    private static final DateTimeFormatter GTFS_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final LocalDate DEFAULT_DATE = LocalDate.of(2026, 6, 30);
    private static final int DEFAULT_FROM_SECONDS = timeSeconds("05:00");
    private static final int DEFAULT_TO_SECONDS = timeSeconds("09:00");
    private static final int DEFAULT_LATEST_ARRIVAL_SECONDS = timeSeconds("11:30");
    private static final int TOP_STOP_SEARCH_HITS = 10;
    private static final int DIRECT_LIMIT = 8;
    private static final int FIRST_LEG_LIMIT = 60;
    private static final int ONE_TRANSFER_RESULT_LIMIT = 8;
    private static final int TWO_TRANSFER_RESULT_LIMIT = 5;
    private static final int MIN_TRANSFER_MINUTES = 3;
    private static final int MAX_TRANSFER_WAIT_MINUTES = 45;
    private static final int MAX_TWO_TRANSFER_WAIT_MINUTES = 35;
    private static final int MAX_TOTAL_TWO_TRANSFER_WAIT_MINUTES = 60;
    private static final int MAX_RELATION_MILLIS = 2_500;

    private SqliteRoutingCoverageMatrix() {
    }

    public static void main(String[] args) {
        try {
            run(Options.parse(args));
        } catch (Exception exception) {
            System.err.println("SqliteRoutingCoverageMatrix failed: " + exception.getMessage());
            exception.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(Options options) throws Exception {
        if (!Files.isRegularFile(options.database())) {
            throw new IllegalArgumentException("SQLite database not found: " + options.database().toAbsolutePath());
        }
        Files.createDirectories(options.markdownOutput().toAbsolutePath().getParent());
        Files.createDirectories(options.csvOutput().toAbsolutePath().getParent());

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + options.database().toAbsolutePath());
             SqliteTransitDataAccess dataAccess = new SqliteTransitDataAccess(options.database())) {
            configureReadOnly(connection);
            List<Relation> baseRelations = relations();
            if (!options.relationFilters().isEmpty()) {
                baseRelations = baseRelations.stream()
                        .filter(relation -> relationMatchesFilters(relation, options.relationFilters()))
                        .toList();
                if (baseRelations.isEmpty()) {
                    throw new IllegalArgumentException("No matrix relations matched filters: " + options.relationFilters());
                }
            }
            List<MatrixCase> matrixCases = matrixCases(baseRelations);
            List<CoverageResult> results = new ArrayList<>();
            Map<String, StationFamily> stationFamilyCache = new LinkedHashMap<>();
            Set<String> activeServiceIds = dataAccess.findActiveServiceIds(options.date());
            long matrixStartedNanos = System.nanoTime();
            int index = 1;
            for (MatrixCase matrixCase : matrixCases) {
                long startedNanos = System.nanoTime();
                CoverageResult result = diagnose(connection, dataAccess, activeServiceIds, stationFamilyCache, matrixCase, options, startedNanos);
                results.add(result);
                System.out.println("%03d/%03d %-42s -> %-42s %-10s %s direct=%d one=%d two=%s elapsed=%dms".formatted(
                        index++,
                        matrixCases.size(),
                        matrixCase.relation().startQuery(),
                        matrixCase.relation().targetQuery(),
                        matrixCase.scenario().label(),
                        result.classification(),
                        result.directCount(),
                        result.oneTransferCount(),
                        result.twoTransferCountText(),
                        result.elapsedMs()
                ));
            }
            long elapsedMs = elapsedMillis(matrixStartedNanos);
            writeMarkdown(options, results, activeServiceIds.size(), elapsedMs, baseRelations.size(), matrixCases.size());
            writeCsv(options, results);
            System.out.println("wrote " + options.markdownOutput().toAbsolutePath());
            System.out.println("wrote " + options.csvOutput().toAbsolutePath());
        }
    }

    private static CoverageResult diagnose(
            Connection connection,
            SqliteTransitDataAccess dataAccess,
            Set<String> activeServiceIds,
            Map<String, StationFamily> stationFamilyCache,
            MatrixCase matrixCase,
            Options options,
            long startedNanos
    ) throws SQLException {
        Relation relation = matrixCase.relation();
        List<StopSearchHit> startHits = stopSearch(connection, relation.startQuery(), TOP_STOP_SEARCH_HITS);
        List<StopSearchHit> targetHits = stopSearch(connection, relation.targetQuery(), TOP_STOP_SEARCH_HITS);
        if (startHits.isEmpty() || targetHits.isEmpty()) {
            return CoverageResult.unresolved(matrixCase, startHits, targetHits, elapsedMillis(startedNanos));
        }

        StopSearchHit start = startHits.get(0);
        StationFamily startFamily = stationFamily(connection, stationFamilyCache, start);
        StopSearchHit target = contextualTargetHit(connection, stationFamilyCache, relation.targetQuery(), targetHits, start, startFamily);
        StationFamily targetFamily = stationFamily(connection, stationFamilyCache, target);
        boolean startHasDepartures = hasActiveDepartures(dataAccess, activeServiceIds, startFamily, matrixCase, options);
        boolean targetHasDepartures = hasActiveDepartures(dataAccess, activeServiceIds, targetFamily, matrixCase, options);
        DirectScan direct = scanDirect(
                dataAccess,
                startFamily,
                targetFamily,
                matrixCase,
                options
        );
        String bestSummary = direct.bestSummary();
        int oneCount = 0;
        String oneSummary = "";
        boolean timedOut = false;
        long deadlineNanos = startedNanos + MAX_RELATION_MILLIS * 1_000_000L;

        if (direct.count() == 0 && !activeServiceIds.isEmpty()) {
            OneTransferScan oneTransfer = scanOneTransfer(
                    dataAccess,
                    activeServiceIds,
                    startFamily,
                    targetFamily,
                    matrixCase,
                    options,
                    deadlineNanos
            );
            oneCount = oneTransfer.count();
            oneSummary = oneTransfer.bestSummary();
            timedOut = oneTransfer.timedOut();
            if (oneCount > 0) {
                bestSummary = oneSummary;
            }
        }

        TwoTransferScan twoTransfer = TwoTransferScan.skippedResult();
        if (direct.count() == 0 && oneCount == 0 && !timedOut && !activeServiceIds.isEmpty()) {
            twoTransfer = scanTwoTransfer(
                    dataAccess,
                    activeServiceIds,
                    startFamily,
                    targetFamily,
                    matrixCase,
                    options,
                    deadlineNanos
            );
            timedOut = twoTransfer.timedOut();
            if (twoTransfer.count() > 0) {
                bestSummary = twoTransfer.bestSummary();
            }
        }

        String classification;
        if (direct.count() > 0) {
            classification = "FOUND_DIRECT";
        } else if (oneCount > 0) {
            classification = "FOUND_ONE_TRANSFER";
        } else if (twoTransfer.count() > 0) {
            classification = "FOUND_TWO_TRANSFER";
        } else if (activeServiceIds.isEmpty()) {
            classification = "NOT_FOUND_CALENDAR_OR_TIME_WINDOW";
        } else if (timedOut) {
            classification = "NOT_FOUND_TIMEOUT";
        } else {
            classification = "UNKNOWN";
        }
        String suspectedCause = suspectedCause(
                relation,
                start,
                target,
                startFamily,
                targetFamily,
                classification,
                timedOut,
                startHasDepartures,
                targetHasDepartures
        );
        String evidence = evidence(start, target, startFamily, targetFamily, startHasDepartures, targetHasDepartures);

        return new CoverageResult(
                matrixCase,
                startHits,
                targetHits,
                start,
                target,
                direct.count(),
                oneCount,
                twoTransfer.count(),
                twoTransfer.skipped(),
                bestSummary,
                classification,
                suspectedCause,
                evidence,
                timedOut,
                elapsedMillis(startedNanos)
        );
    }

    private static boolean hasActiveDepartures(
            SqliteTransitDataAccess dataAccess,
            Set<String> activeServiceIds,
            StationFamily family,
            MatrixCase matrixCase,
            Options options
    ) throws SQLException {
        if (activeServiceIds.isEmpty()) {
            return false;
        }
        for (FamilyMember member : family.routingMembers()) {
            int fromSeconds = Math.min(matrixCase.toSeconds(), matrixCase.fromSeconds() + member.accessCostMinutes() * 60);
            if (!dataAccess.findDepartures(
                    member.areaId(),
                    fromSeconds,
                    matrixCase.toSeconds(),
                    activeServiceIds,
                    1
            ).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static DirectScan scanDirect(
            SqliteTransitDataAccess dataAccess,
            StationFamily startFamily,
            StationFamily targetFamily,
            MatrixCase matrixCase,
            Options options
    ) throws SQLException {
        int count = 0;
        String bestSummary = "";
        for (FamilyMember startMember : startFamily.routingMembers()) {
            int fromSeconds = Math.min(matrixCase.toSeconds(), matrixCase.fromSeconds() + startMember.accessCostMinutes() * 60);
            for (FamilyMember targetMember : targetFamily.routingMembers()) {
                int latestArrivalSeconds = matrixCase.latestArrivalSeconds() - targetMember.accessCostMinutes() * 60;
                if (latestArrivalSeconds <= fromSeconds) {
                    continue;
                }
                List<DirectTransitLegData> direct = dataAccess.findDirectLegs(
                        startMember.areaId(),
                        targetMember.areaId(),
                        options.date(),
                        fromSeconds,
                        matrixCase.toSeconds(),
                        DIRECT_LIMIT
                );
                for (DirectTransitLegData leg : direct) {
                    if (leg.targetArrivalSeconds() > latestArrivalSeconds) {
                        continue;
                    }
                    count++;
                    if (bestSummary.isBlank()) {
                        bestSummary = directSummary(leg, startMember, targetMember);
                    }
                }
            }
        }
        return new DirectScan(count, bestSummary);
    }

    private static OneTransferScan scanOneTransfer(
            SqliteTransitDataAccess dataAccess,
            Set<String> activeServiceIds,
            StationFamily startFamily,
            StationFamily targetFamily,
            MatrixCase matrixCase,
            Options options,
            long deadlineNanos
    ) throws SQLException {
        int count = 0;
        String best = "";
        boolean timedOut = false;
        for (FamilyMember startMember : startFamily.routingMembers()) {
            for (FamilyMember targetMember : targetFamily.routingMembers()) {
                OneTransferScan scan = scanOneTransfer(
                        dataAccess,
                        activeServiceIds,
                        startMember,
                        targetMember,
                        matrixCase,
                        options,
                        deadlineNanos
                );
                count += scan.count();
                if (best.isBlank() && !scan.bestSummary().isBlank()) {
                    best = scan.bestSummary();
                }
                timedOut = timedOut || scan.timedOut();
                if (timedOut) {
                    break;
                }
            }
            if (timedOut) {
                break;
            }
        }
        return new OneTransferScan(count, best, timedOut);
    }

    private static OneTransferScan scanOneTransfer(
            SqliteTransitDataAccess dataAccess,
            Set<String> activeServiceIds,
            FamilyMember startMember,
            FamilyMember targetMember,
            MatrixCase matrixCase,
            Options options,
            long deadlineNanos
    ) throws SQLException {
        String startAreaId = startMember.areaId();
        String targetAreaId = targetMember.areaId();
        int fromSeconds = Math.min(matrixCase.toSeconds(), matrixCase.fromSeconds() + startMember.accessCostMinutes() * 60);
        int latestArrivalSeconds = matrixCase.latestArrivalSeconds() - targetMember.accessCostMinutes() * 60;
        List<NextTransitLegData> firstLegs = dataAccess.findNextLegs(
                startAreaId,
                options.date(),
                fromSeconds,
                matrixCase.toSeconds(),
                0,
                activeServiceIds,
                FIRST_LEG_LIMIT
        );
        int count = 0;
        String best = "";
        for (NextTransitLegData firstLeg : firstLegs) {
            if (System.nanoTime() > deadlineNanos) {
                return new OneTransferScan(count, best, true);
            }
            if (targetAreaId.equals(firstLeg.toAreaId()) || startAreaId.equals(firstLeg.toAreaId())) {
                continue;
            }
            int earliestSecondDeparture = firstLeg.arrivalSeconds() + MIN_TRANSFER_MINUTES * 60;
            int latestSecondDeparture = Math.min(firstLeg.arrivalSeconds() + MAX_TRANSFER_WAIT_MINUTES * 60, latestArrivalSeconds);
            if (latestSecondDeparture <= earliestSecondDeparture) {
                continue;
            }
            List<DirectTransitLegData> secondLegs = dataAccess.findDirectLegs(
                    firstLeg.toAreaId(),
                    targetAreaId,
                    options.date(),
                    earliestSecondDeparture,
                    latestSecondDeparture,
                    ONE_TRANSFER_RESULT_LIMIT
            );
            for (DirectTransitLegData secondLeg : secondLegs) {
                int waitMinutes = Math.max(0, (secondLeg.startDepartureSeconds() - firstLeg.arrivalSeconds()) / 60);
                count++;
                if (best.isBlank()) {
                    best = oneTransferSummary(firstLeg, secondLeg, waitMinutes);
                }
            }
        }
        return new OneTransferScan(count, best, false);
    }

    private static TwoTransferScan scanTwoTransfer(
            SqliteTransitDataAccess dataAccess,
            Set<String> activeServiceIds,
            StationFamily startFamily,
            StationFamily targetFamily,
            MatrixCase matrixCase,
            Options options,
            long deadlineNanos
    ) throws SQLException {
        int count = 0;
        String best = "";
        boolean timedOut = false;
        for (FamilyMember startMember : startFamily.routingMembers()) {
            for (FamilyMember targetMember : targetFamily.routingMembers()) {
                TwoTransferScan scan = scanTwoTransfer(
                        dataAccess,
                        activeServiceIds,
                        startMember,
                        targetMember,
                        matrixCase,
                        options,
                        deadlineNanos
                );
                count += scan.count();
                if (best.isBlank() && !scan.bestSummary().isBlank()) {
                    best = scan.bestSummary();
                }
                timedOut = timedOut || scan.timedOut();
                if (timedOut) {
                    break;
                }
            }
            if (timedOut) {
                break;
            }
        }
        return new TwoTransferScan(count, best, timedOut, false);
    }

    private static TwoTransferScan scanTwoTransfer(
            SqliteTransitDataAccess dataAccess,
            Set<String> activeServiceIds,
            FamilyMember startMember,
            FamilyMember targetMember,
            MatrixCase matrixCase,
            Options options,
            long deadlineNanos
    ) throws SQLException {
        String startAreaId = startMember.areaId();
        String targetAreaId = targetMember.areaId();
        int fromSeconds = Math.min(matrixCase.toSeconds(), matrixCase.fromSeconds() + startMember.accessCostMinutes() * 60);
        int latestArrivalSeconds = matrixCase.latestArrivalSeconds() - targetMember.accessCostMinutes() * 60;
        List<NextTransitLegData> firstLegs = dataAccess.findNextLegs(
                startAreaId,
                options.date(),
                fromSeconds,
                matrixCase.toSeconds(),
                0,
                activeServiceIds,
                30
        );
        int count = 0;
        String best = "";
        Set<String> seen = new LinkedHashSet<>();
        for (NextTransitLegData firstLeg : firstLegs) {
            if (System.nanoTime() > deadlineNanos) {
                return new TwoTransferScan(count, best, true, false);
            }
            if (isStartTargetOrBlank(firstLeg.toAreaId(), startAreaId, targetAreaId)) {
                continue;
            }
            int secondFrom = firstLeg.arrivalSeconds() + MIN_TRANSFER_MINUTES * 60;
            int secondTo = Math.min(firstLeg.arrivalSeconds() + MAX_TWO_TRANSFER_WAIT_MINUTES * 60, matrixCase.latestArrivalSeconds());
            List<NextTransitLegData> secondLegs = dataAccess.findNextLegs(
                    firstLeg.toAreaId(),
                    options.date(),
                    secondFrom,
                    secondTo,
                    0,
                    activeServiceIds,
                    10
            );
            for (NextTransitLegData secondLeg : secondLegs) {
                if (System.nanoTime() > deadlineNanos) {
                    return new TwoTransferScan(count, best, true, false);
                }
                if (isStartTargetOrBlank(secondLeg.toAreaId(), startAreaId, targetAreaId)
                        || firstLeg.toAreaId().equals(secondLeg.toAreaId())
                        || firstLeg.tripId().equals(secondLeg.tripId())) {
                    continue;
                }
                int firstWait = Math.max(0, (secondLeg.departureSeconds() - firstLeg.arrivalSeconds()) / 60);
                int thirdFrom = secondLeg.arrivalSeconds() + MIN_TRANSFER_MINUTES * 60;
                int thirdTo = Math.min(secondLeg.arrivalSeconds() + MAX_TWO_TRANSFER_WAIT_MINUTES * 60, latestArrivalSeconds);
                if (thirdTo <= thirdFrom) {
                    continue;
                }
                List<DirectTransitLegData> thirdLegs = dataAccess.findDirectLegs(
                        secondLeg.toAreaId(),
                        targetAreaId,
                        options.date(),
                        thirdFrom,
                        thirdTo,
                        TWO_TRANSFER_RESULT_LIMIT
                );
                for (DirectTransitLegData thirdLeg : thirdLegs) {
                    int secondWait = Math.max(0, (thirdLeg.startDepartureSeconds() - secondLeg.arrivalSeconds()) / 60);
                    if (firstWait + secondWait > MAX_TOTAL_TWO_TRANSFER_WAIT_MINUTES) {
                        continue;
                    }
                    String key = firstLeg.tripId() + "|" + secondLeg.tripId() + "|" + thirdLeg.tripId();
                    if (!seen.add(key)) {
                        continue;
                    }
                    count++;
                    if (best.isBlank()) {
                        best = twoTransferSummary(firstLeg, secondLeg, thirdLeg, firstWait, secondWait);
                    }
                }
            }
        }
        return new TwoTransferScan(count, best, false, false);
    }

    private static StationFamily stationFamily(Connection connection, StopSearchHit hit) throws SQLException {
        if (!tableExists(connection, "canonical_stop_area_members")
                || !tableExists(connection, "canonical_stop_areas")) {
            return StationFamily.single(hit);
        }
        String canonicalAreaId = canonicalAreaIdForArea(connection, hit.areaId());
        if (canonicalAreaId == null || canonicalAreaId.isBlank()) {
            return StationFamily.single(hit);
        }
        boolean hasRoutingColumn = columnExists(connection, "canonical_stop_area_members", "is_primary_for_routing");
        boolean hasSearchColumn = columnExists(connection, "canonical_stop_area_members", "is_primary_for_search");
        boolean hasVisibleColumn = columnExists(connection, "canonical_stop_area_members", "is_visible_suggestion");
        boolean hasAccessColumn = columnExists(connection, "canonical_stop_area_members", "access_cost_minutes");
        boolean hasDisplayRoleColumn = columnExists(connection, "canonical_stop_area_members", "display_role");

        String sql = """
                SELECT canonical.canonical_area_id,
                       canonical.canonical_display_name,
                       canonical.primary_stop_area_id,
                       member.area_id,
                       member.member_role,
                       member.quality,
                       member.distance_meters,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.line_labels, '') AS line_labels
                """
                + (hasDisplayRoleColumn ? ", member.display_role AS display_role\n" : ", '' AS display_role\n")
                + (hasSearchColumn ? ", member.is_primary_for_search AS is_primary_for_search\n" : ", CASE WHEN member.member_role = 'PRIMARY_RAIL' THEN 1 ELSE 0 END AS is_primary_for_search\n")
                + (hasRoutingColumn ? ", member.is_primary_for_routing AS is_primary_for_routing\n" : ", CASE WHEN member.member_role = 'PRIMARY_RAIL' THEN 1 ELSE 0 END AS is_primary_for_routing\n")
                + (hasVisibleColumn ? ", member.is_visible_suggestion AS is_visible_suggestion\n" : ", CASE WHEN member.member_role = 'BUS_FEEDER' THEN 0 ELSE 1 END AS is_visible_suggestion\n")
                + (hasAccessColumn ? ", member.access_cost_minutes AS access_cost_minutes\n" : ", COALESCE(CASE WHEN member.distance_meters <= 80 THEN 2 WHEN member.distance_meters <= 200 THEN 4 WHEN member.distance_meters <= 400 THEN 6 WHEN member.distance_meters IS NULL THEN 0 ELSE 10 END, 0) AS access_cost_minutes\n")
                + """
                FROM canonical_stop_area_members member
                JOIN canonical_stop_areas canonical ON canonical.canonical_area_id = member.canonical_area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = member.area_id
                WHERE member.canonical_area_id = ?
                ORDER BY is_primary_for_routing DESC,
                         is_primary_for_search DESC,
                         access_cost_minutes,
                         member.member_role,
                         member.area_id
                LIMIT 12
                """;
        List<FamilyMember> members = new ArrayList<>();
        String familyName = hit.areaName();
        String primaryAreaId = hit.areaId();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, canonicalAreaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    familyName = resultSet.getString("canonical_display_name");
                    primaryAreaId = resultSet.getString("primary_stop_area_id");
                    members.add(new FamilyMember(
                            resultSet.getString("area_id"),
                            resultSet.getString("member_role"),
                            resultSet.getString("display_role"),
                            resultSet.getInt("is_primary_for_search") != 0,
                            resultSet.getInt("is_primary_for_routing") != 0,
                            resultSet.getInt("is_visible_suggestion") != 0,
                            Math.max(0, resultSet.getInt("access_cost_minutes")),
                            resultSet.getString("quality"),
                            resultSet.getInt("distance_meters"),
                            resultSet.getString("profile_class"),
                            resultSet.getInt("has_rail_service") != 0,
                            resultSet.getInt("bus_only") != 0,
                            resultSet.getString("line_labels")
                    ));
                }
            }
        }
        if (members.isEmpty()) {
            return StationFamily.single(hit);
        }
        boolean hitIsIncluded = members.stream().anyMatch(member -> member.areaId().equals(hit.areaId()));
        if (!hitIsIncluded) {
            members.add(FamilyMember.fromHit(hit));
        }
        return new StationFamily(canonicalAreaId, familyName, primaryAreaId, hit.areaId(), List.copyOf(members));
    }

    private static String canonicalAreaIdForArea(Connection connection, String areaId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_area_id
                FROM canonical_stop_area_members
                WHERE area_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("canonical_area_id");
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT canonical_area_id
                FROM canonical_stop_areas
                WHERE primary_stop_area_id = ?
                LIMIT 1
                """)) {
            statement.setString(1, areaId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("canonical_area_id");
                }
            }
        }
        return null;
    }

    private static List<StopSearchHit> stopSearch(Connection connection, String query, int limit) throws SQLException {
        String normalized = StopNameNormalizer.normalize(query);
        if (normalized.isBlank()) {
            return List.of();
        }
        List<String> tokens = queryTokens(normalized);
        Map<String, MutableStopSearchHit> hits = new LinkedHashMap<>();
        collectCanonicalNameMatches(connection, hits, normalized);
        collectAliasMatches(connection, hits, normalized);
        collectTokenMatches(connection, hits, tokens);
        applyStopSearchQualityScores(hits, normalized, tokens);
        if (stationIntent(normalized) && hasSpecificQueryTokens(tokens)) {
            hits.entrySet().removeIf(entry -> entry.getValue().missingSpecificTokens > 0);
        }
        List<StopSearchHit> ranked = hits.values().stream()
                .map(MutableStopSearchHit::toHit)
                .sorted(Comparator
                        .comparingInt(StopSearchHit::score).reversed()
                        .thenComparing(StopSearchHit::areaName)
                        .thenComparing(StopSearchHit::areaId))
                .limit(limit)
                .toList();
        return List.copyOf(ranked);
    }

    private static StopSearchHit contextualTargetHit(
            Connection connection,
            Map<String, StationFamily> stationFamilyCache,
            String targetQuery,
            List<StopSearchHit> targetHits,
            StopSearchHit start,
            StationFamily startFamily
    ) throws SQLException {
        if (targetHits.isEmpty()) {
            throw new IllegalArgumentException("targetHits must not be empty");
        }
        String normalizedTargetQuery = StopNameNormalizer.normalize(targetQuery);
        if (!directionalStationIntent(normalizedTargetQuery) && !genericStationQuery(normalizedTargetQuery)) {
            return targetHits.get(0);
        }

        Set<String> contextTokens = contextTokens(start.areaName() + " " + startFamily.displayName());
        if (contextTokens.isEmpty()) {
            return targetHits.get(0);
        }

        StopSearchHit best = targetHits.get(0);
        int bestScore = Integer.MIN_VALUE;
        for (StopSearchHit hit : targetHits) {
            StationFamily family = stationFamily(connection, stationFamilyCache, hit);
            String searchableName = StopNameNormalizer.normalize(hit.areaName() + " " + family.displayName());
            int score = hit.score();
            for (String token : contextTokens) {
                if (containsToken(searchableName, token)) {
                    score += 120_000;
                }
            }
            if (hit.hasRailService() || family.members().stream().anyMatch(FamilyMember::hasRailService)) {
                score += 8_000;
            }
            if (StopNameNormalizer.normalize(family.displayName()).equals(normalizedTargetQuery)
                    && contextTokens.stream().noneMatch(token -> containsToken(searchableName, token))) {
                score -= 20_000;
            }
            if (score > bestScore) {
                bestScore = score;
                best = hit;
            }
        }
        return best;
    }

    private static StationFamily stationFamily(
            Connection connection,
            Map<String, StationFamily> cache,
            StopSearchHit hit
    ) throws SQLException {
        StationFamily cached = cache.get(hit.areaId());
        if (cached != null) {
            return cached;
        }
        StationFamily family = stationFamily(connection, hit);
        cache.put(hit.areaId(), family);
        return family;
    }

    private static void collectCanonicalNameMatches(
            Connection connection,
            Map<String, MutableStopSearchHit> hits,
            String normalized
    ) throws SQLException {
        if (!tableExists(connection, "canonical_stop_area_names")) {
            return;
        }
        String sql = """
                SELECT canonical.primary_stop_area_id AS area_id,
                       name.display_name AS area_name,
                       name.display_name_normalized,
                       COALESCE(profile.search_priority_score, 0) AS profile_score,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.route_types, '') AS route_types,
                       COALESCE(profile.line_labels, '') AS line_labels,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.stop_count, 0) AS stop_count
                FROM canonical_stop_area_names name
                JOIN canonical_stop_areas canonical ON canonical.canonical_area_id = name.canonical_area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = canonical.primary_stop_area_id
                WHERE name.display_name_normalized = ?
                   OR name.display_name_normalized LIKE ?
                LIMIT 80
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setString(2, normalized + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MutableStopSearchHit hit = hit(hits, resultSet);
                    int exactBoost = normalized.equals(resultSet.getString("display_name_normalized")) ? 45_000 : 18_000;
                    hit.score += exactBoost + resultSet.getInt("profile_score");
                    addMatchedTokens(hit, resultSet.getString("display_name_normalized"));
                    hit.matchedSignals.add("canonicalName");
                }
            }
        }
    }

    private static void collectAliasMatches(
            Connection connection,
            Map<String, MutableStopSearchHit> hits,
            String normalized
    ) throws SQLException {
        String sql = """
                SELECT sa.area_id,
                       area.area_name,
                       alias.alias,
                       alias.alias_normalized,
                       alias.alias_type,
                       alias.priority,
                       COALESCE(profile.search_priority_score, 0) AS profile_score,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.route_types, '') AS route_types,
                       COALESCE(profile.line_labels, '') AS line_labels,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.stop_count, 0) AS stop_count
                FROM stop_area_aliases alias
                JOIN stop_areas area ON area.area_id = alias.area_id
                JOIN stop_areas sa ON sa.area_id = alias.area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = alias.area_id
                WHERE alias.alias_normalized = ?
                   OR alias.alias_normalized LIKE ?
                LIMIT 80
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalized);
            statement.setString(2, normalized + "%");
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MutableStopSearchHit hit = hit(hits, resultSet);
                    int exactBoost = normalized.equals(resultSet.getString("alias_normalized")) ? 20_000 : 8_000;
                    hit.score += exactBoost + resultSet.getInt("priority") * 100 + resultSet.getInt("profile_score");
                    addMatchedTokens(hit, resultSet.getString("alias_normalized"));
                    hit.matchedSignals.add("alias:" + resultSet.getString("alias_type"));
                }
            }
        }
    }

    private static void collectTokenMatches(
            Connection connection,
            Map<String, MutableStopSearchHit> hits,
            List<String> tokens
    ) throws SQLException {
        if (tokens.isEmpty()) {
            return;
        }
        String sql = """
                SELECT token.area_id,
                       area.area_name,
                       COUNT(DISTINCT token.token) AS matched_token_count,
                       GROUP_CONCAT(DISTINCT token.token) AS matched_tokens,
                       COALESCE(profile.search_priority_score, 0) AS profile_score,
                       COALESCE(profile.profile_class, '') AS profile_class,
                       COALESCE(profile.route_types, '') AS route_types,
                       COALESCE(profile.line_labels, '') AS line_labels,
                       COALESCE(profile.has_rail_service, 0) AS has_rail_service,
                       COALESCE(profile.bus_only, 0) AS bus_only,
                       COALESCE(profile.stop_count, 0) AS stop_count
                FROM stop_search_tokens token
                JOIN stop_areas area ON area.area_id = token.area_id
                LEFT JOIN stop_area_profiles profile ON profile.area_id = token.area_id
                WHERE token.token IN (%s)
                GROUP BY token.area_id
                ORDER BY matched_token_count DESC, profile_score DESC, area.area_name
                LIMIT 120
                """.formatted(placeholders(tokens.size()));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < tokens.size(); i++) {
                statement.setString(i + 1, tokens.get(i));
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    MutableStopSearchHit hit = hit(hits, resultSet);
                    int matched = resultSet.getInt("matched_token_count");
                    hit.score += matched * 2_000 + resultSet.getInt("profile_score");
                    addMatchedTokens(hit, resultSet.getString("matched_tokens"));
                    hit.matchedSignals.add("tokens:" + nullToDash(resultSet.getString("matched_tokens")));
                }
            }
        }
    }

    private static MutableStopSearchHit hit(
            Map<String, MutableStopSearchHit> hits,
            ResultSet resultSet
    ) throws SQLException {
        String areaId = resultSet.getString("area_id");
        MutableStopSearchHit hit = hits.get(areaId);
        if (hit == null) {
            hit = new MutableStopSearchHit(
                    areaId,
                    resultSet.getString("area_name"),
                    resultSet.getString("profile_class"),
                    resultSet.getString("route_types"),
                    resultSet.getString("line_labels"),
                    resultSet.getInt("has_rail_service") == 1,
                    resultSet.getInt("bus_only") == 1,
                    resultSet.getInt("stop_count")
            );
            hits.put(areaId, hit);
        }
        return hit;
    }

    private static void applyStopSearchQualityScores(
            Map<String, MutableStopSearchHit> hits,
            String normalizedQuery,
            List<String> queryTokens
    ) {
        boolean stationIntent = stationIntent(normalizedQuery);
        boolean railPlaceIntent = railPlaceIntent(normalizedQuery, queryTokens);
        boolean mainStationIntent = queryTokens.stream().anyMatch(token ->
                token.equals("hbf") || token.equals("hauptbahnhof"));
        String lastSpecificQueryToken = lastSpecificQueryToken(queryTokens);
        for (MutableStopSearchHit hit : hits.values()) {
            String normalizedName = StopNameNormalizer.normalize(hit.areaName);
            int missingSpecificTokens = missingSpecificTokenCount(hit, normalizedQuery, normalizedName, queryTokens);
            hit.missingSpecificTokens = missingSpecificTokens;
            if (missingSpecificTokens > 0) {
                hit.score -= missingSpecificTokens * 50_000;
                hit.matchedSignals.add("missingSpecificTokens:" + missingSpecificTokens);
            }
            if (!stationIntent && !lastSpecificQueryToken.isBlank() && queryTokens.size() > 1) {
                if (containsToken(normalizedName, lastSpecificQueryToken)
                        || hit.matchedTokens.contains(lastSpecificQueryToken)) {
                    hit.score += 12_000;
                    hit.matchedSignals.add("placeQuery:lastSpecificTokenBoost:" + lastSpecificQueryToken);
                } else {
                    hit.score -= 12_000;
                    hit.matchedSignals.add("placeQuery:missingLastSpecificToken:" + lastSpecificQueryToken);
                }
            }
            if (!stationIntent && railPlaceIntent && queryTokens.size() > 1 && missingSpecificTokens > 0) {
                hit.matchedSignals.add("railPlaceIntent:skipPartialPlaceMatch");
                continue;
            }
            if (!stationIntent && !railPlaceIntent) {
                continue;
            }
            boolean busLikeName = containsBusLikeStationDistractor(normalizedName);
            boolean mainStationName = normalizedName.contains("hbf")
                    || normalizedName.contains("hauptbahnhof");
            boolean stationName = containsStationNameSignal(normalizedName);

            if (hit.hasRailService) {
                hit.score += stationIntent ? 18_000 : 9_000;
                if (stationName) {
                    hit.score += stationIntent ? 6_000 : 3_000;
                }
                if (mainStationIntent && mainStationName) {
                    hit.score += 10_000;
                }
                if ("MAIN_RAIL".equals(hit.profileClass)) {
                    hit.score += stationIntent ? 8_000 : 5_000;
                } else if (isRailProfile(hit.profileClass)) {
                    hit.score += stationIntent ? 4_000 : 2_500;
                }
            } else {
                hit.score -= stationIntent ? 18_000 : 8_000;
                if (mainStationIntent) {
                    hit.score -= 20_000;
                    hit.matchedSignals.add("mainStationIntent:nonRailPenalty");
                }
            }

            if (hit.busOnly) {
                hit.score -= stationIntent ? 35_000 : 32_000;
                hit.matchedSignals.add((stationIntent ? "stationIntent" : "railPlaceIntent") + ":busOnlyPenalty");
            }
            if (busLikeName) {
                hit.score -= hit.busOnly || !hit.hasRailService
                        ? (stationIntent ? 25_000 : 18_000)
                        : (stationIntent ? 8_000 : 5_000);
                hit.matchedSignals.add((stationIntent ? "stationIntent" : "railPlaceIntent") + ":busLikeNamePenalty");
            }
            if (mainStationIntent && !mainStationName) {
                hit.score -= 6_000;
            }
            if (railPlaceIntent && queryTokens.size() == 1) {
                String queryToken = queryTokens.get(0);
                if (normalizedName.equals(queryToken + " bahnhof")
                        || normalizedName.equals(queryToken + " hbf")
                        || normalizedName.equals(queryToken + " hauptbahnhof")) {
                    hit.score += hit.hasRailService ? 80_000 : 20_000;
                    hit.matchedSignals.add("railPlaceIntent:cityStationExactBoost");
                }
                if (hit.hasRailService
                        && normalizedName.startsWith(queryToken + " ")
                        && containsStationNameSignal(normalizedName)
                        && !hit.busOnly) {
                    hit.score += 16_000;
                    hit.matchedSignals.add("railPlaceIntent:railStationPrefixBoost");
                }
                if (hit.hasRailService
                        && normalizedName.startsWith(queryToken + " ")
                        && containsStationNameSignal(normalizedName)
                        && !normalizedName.equals(queryToken + " bahnhof")
                        && !normalizedName.equals(queryToken + " hbf")
                        && !normalizedName.equals(queryToken + " hauptbahnhof")) {
                    hit.score -= 24_000;
                    hit.matchedSignals.add("railPlaceIntent:extraStationQualifierPenalty");
                }
                if ((normalizedName.contains(" ost")
                        || normalizedName.contains(" west")
                        || normalizedName.contains(" nord")
                        || normalizedName.contains(" sued")
                        || normalizedName.contains(" sud"))
                        && !containsToken(normalizedQuery, "ost")
                        && !containsToken(normalizedQuery, "west")
                        && !containsToken(normalizedQuery, "nord")
                        && !containsToken(normalizedQuery, "sued")
                        && !containsToken(normalizedQuery, "sud")) {
                    hit.score -= hit.hasRailService && !hit.busOnly ? 12_000 : 30_000;
                    hit.matchedSignals.add("railPlaceIntent:directionalDistrictPenalty");
                }
            }
        }
    }

    private static boolean hasSpecificQueryTokens(List<String> queryTokens) {
        return queryTokens.stream().anyMatch(token -> !isGenericStationToken(token));
    }

    private static void addMatchedTokens(MutableStopSearchHit hit, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        for (String token : value.split("[,\\s]+")) {
            String normalized = StopNameNormalizer.normalize(token);
            if (!normalized.isBlank()) {
                hit.matchedTokens.add(normalized);
            }
        }
    }

    private static int missingSpecificTokenCount(
            MutableStopSearchHit hit,
            String normalizedQuery,
            String normalizedName,
            List<String> queryTokens
    ) {
        int missing = 0;
        Map<String, Integer> nameTokenCounts = tokenCounts(normalizedName);
        Map<String, Integer> matchedTokenCounts = tokenCounts(hit.matchedTokens);
        Map<String, Integer> queryTokenCounts = tokenCountsWithDuplicates(normalizedQuery);
        if (queryTokenCounts.isEmpty()) {
            queryTokenCounts = tokenCounts(queryTokens);
        }
        for (Map.Entry<String, Integer> entry : queryTokenCounts.entrySet()) {
            String token = entry.getKey();
            if (isGenericStationToken(token)) {
                continue;
            }
            int required = entry.getValue();
            int matched = Math.max(nameTokenCounts.getOrDefault(token, 0), matchedTokenCounts.getOrDefault(token, 0));
            if (matched < required) {
                missing += required - matched;
            }
        }
        return missing;
    }

    private static Map<String, Integer> tokenCountsWithDuplicates(String normalized) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (normalized == null || normalized.isBlank()) {
            return counts;
        }
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> tokenCounts(List<String> tokens) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokens) {
            if (token != null && token.length() >= 2) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> tokenCounts(Set<String> tokens) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : tokens) {
            if (token != null && token.length() >= 2) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        return counts;
    }

    private static Map<String, Integer> tokenCounts(String normalized) {
        return tokenCountsWithDuplicates(normalized);
    }

    private static boolean railPlaceIntent(String normalizedQuery, List<String> queryTokens) {
        if (stationIntent(normalizedQuery)) {
            return false;
        }
        long specificCount = queryTokens.stream()
                .filter(token -> !isGenericStationToken(token))
                .count();
        return specificCount > 0 && specificCount <= 2;
    }

    private static String lastSpecificQueryToken(List<String> queryTokens) {
        for (int index = queryTokens.size() - 1; index >= 0; index--) {
            String token = queryTokens.get(index);
            if (!isGenericStationToken(token)) {
                return token;
            }
        }
        return "";
    }

    private static boolean genericStationQuery(String normalizedQuery) {
        return normalizedQuery.equals("bahnhof")
                || normalizedQuery.equals("hbf")
                || normalizedQuery.equals("hauptbahnhof")
                || normalizedQuery.equals("ostbahnhof")
                || normalizedQuery.equals("westbahnhof")
                || normalizedQuery.equals("nordbahnhof")
                || normalizedQuery.equals("suedbahnhof")
                || normalizedQuery.equals("sudbahnhof");
    }

    private static Set<String> contextTokens(String value) {
        Set<String> tokens = new LinkedHashSet<>();
        String normalized = StopNameNormalizer.normalize(value);
        for (String token : normalized.split("\\s+")) {
            if (token.length() < 4
                    || isGenericStationToken(token)
                    || isDirectionalContextStopWord(token)
                    || "ber".equals(token)
                    || "hbf".equals(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isDirectionalContextStopWord(String token) {
        return switch (token) {
            case "ost", "west", "nord", "sued", "sud", "kreuz", "bahnhof", "hauptbahnhof",
                    "station", "platz", "strasse", "str", "zob", "bus" -> true;
            default -> false;
        };
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static boolean isGenericStationToken(String token) {
        return switch (token) {
            case "hbf", "hauptbahnhof", "bf", "bahnhof" -> true;
            default -> false;
        };
    }

    private static boolean isRailProfile(String profileClass) {
        return "MAIN_RAIL".equals(profileClass)
                || "RAIL".equals(profileClass)
                || "RAIL_LOCAL".equals(profileClass)
                || "RAIL_HUB".equals(profileClass)
                || "METRO_RAIL".equals(profileClass)
                || "URBAN_RAIL".equals(profileClass);
    }

    private static boolean containsStationNameSignal(String normalizedName) {
        return normalizedName.contains("hbf")
                || normalizedName.contains("hauptbahnhof")
                || normalizedName.contains("bahnhof")
                || normalizedName.contains(" bf")
                || normalizedName.endsWith("bf");
    }

    private static boolean containsBusLikeStationDistractor(String normalizedName) {
        return normalizedName.contains("zob")
                || normalizedName.contains("bus")
                || normalizedName.contains("bahnhofstr")
                || normalizedName.contains("bahnhofstrasse")
                || normalizedName.contains("fischmarkt")
                || normalizedName.contains("kaiserstr")
                || normalizedName.contains("kaiserstrasse");
    }

    private static void writeMarkdown(
            Options options,
            List<CoverageResult> results,
            int activeServiceCount,
            long elapsedMs,
            int relationCount,
            int caseCount
    ) throws IOException {
        Map<String, Long> byClass = new LinkedHashMap<>();
        for (CoverageResult result : results) {
            byClass.merge(result.classification(), 1L, Long::sum);
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("# IXIT Routing Coverage Matrix Germany v0.1").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("Stand: 2026-07-05").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("## Ziel").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("Diese Matrix prueft ").append(caseCount).append(" deutschlandweite Routing-Faelle aus ")
                .append(relationCount).append(" Basisrelationen und vier Tageslagen gegen die app-ready SQLite-DB. ")
                .append("Sie ist Diagnose, kein Produkt-Routing und kein UI-Test.").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("## Setup").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("- DB: `").append(options.database()).append("`").append(System.lineSeparator());
        markdown.append("- Datum: `").append(options.date()).append("`").append(System.lineSeparator());
        markdown.append("- Basisrelationen: `").append(relationCount).append("`").append(System.lineSeparator());
        if (!options.relationFilters().isEmpty()) {
            markdown.append("- Relationsfilter: `").append(options.relationFilters()).append("`").append(System.lineSeparator());
        }
        markdown.append("- Szenarien: `").append(scenarioSummary()).append("`").append(System.lineSeparator());
        markdown.append("- aktive Services: `").append(activeServiceCount).append("`").append(System.lineSeparator());
        markdown.append("- Laufzeit Matrix: `").append(elapsedMs).append(" ms`").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("TwoTransfer wird in v0.1 nur fuer Relationen ohne Direct- und OneTransfer-Ergebnis gesucht, ")
                .append("damit die Diagnose schnell und reproduzierbar bleibt.").append(System.lineSeparator()).append(System.lineSeparator());

        markdown.append("## Ergebnisuebersicht").append(System.lineSeparator()).append(System.lineSeparator());
        for (Map.Entry<String, Long> entry : byClass.entrySet()) {
            markdown.append("- `").append(entry.getKey()).append("`: `").append(entry.getValue()).append("`").append(System.lineSeparator());
        }
        markdown.append(System.lineSeparator());

        long foundCount = results.stream()
                .filter(result -> result.classification().startsWith("FOUND"))
                .count();
        markdown.append("## Erste Befunde").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("- Gefunden: `").append(foundCount).append("/").append(results.size()).append("`.").append(System.lineSeparator());
        markdown.append("- Nicht gefunden oder abgebrochen: `").append(results.size() - foundCount).append("/").append(results.size()).append("`.").append(System.lineSeparator());
        markdown.append("- Viele Hbf-zu-Hbf-Relationen sind bereits Direct-gruen.").append(System.lineSeparator());
        markdown.append("- Offene Faelle zeigen haeufig StopArea-Top1-Signale, die nach Busstop, Platz, ZOB oder falschem Bahnhofsteil aussehen.").append(System.lineSeparator());
        markdown.append("- `NOT_FOUND_TIMEOUT` bedeutet in dieser v0.1-Diagnose: Der konservative Kandidatenraum wurde nicht schnell genug leer oder erfolgreich aufgeloest.").append(System.lineSeparator());
        markdown.append("- `UNKNOWN` bedeutet: Direct/One/konservatives TwoTransfer fanden nichts, ohne das harte Zeitbudget zu reissen.").append(System.lineSeparator());
        markdown.append(System.lineSeparator());

        markdown.append("### Nicht gefundene Relationen").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("| # | Szenario | Relation | Start Top1 | Ziel Top1 | Klasse | Verdacht | Evidenz | Zeit |").append(System.lineSeparator());
        markdown.append("| ---: | --- | --- | --- | --- | --- | --- | --- | ---: |").append(System.lineSeparator());
        for (int i = 0; i < results.size(); i++) {
            CoverageResult result = results.get(i);
            if (result.classification().startsWith("FOUND")) {
                continue;
            }
            markdown.append("| ").append(i + 1)
                    .append(" | ").append(escape(result.scenarioSummary()))
                    .append(" | ").append(escape(result.relation().startQuery())).append(" -> ").append(escape(result.relation().targetQuery()))
                    .append(" | ").append(escape(result.startTop()))
                    .append(" | ").append(escape(result.targetTop()))
                    .append(" | `").append(result.classification()).append("`")
                    .append(" | `").append(result.suspectedCause()).append("`")
                    .append(" | ").append(escape(result.evidence()))
                    .append(" | ").append(result.elapsedMs()).append(" ms |")
                    .append(System.lineSeparator());
        }
        markdown.append(System.lineSeparator());

        markdown.append("## Matrix").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("| # | Szenario | Relation | Start Top1 | Ziel Top1 | Direct | One | Two | Klasse | Verdacht | Bestes Ergebnis | Zeit |").append(System.lineSeparator());
        markdown.append("| ---: | --- | --- | --- | --- | ---: | ---: | ---: | --- | --- | --- | ---: |").append(System.lineSeparator());
        int index = 1;
        for (CoverageResult result : results) {
            markdown.append("| ").append(index++)
                    .append(" | ").append(escape(result.scenarioSummary()))
                    .append(" | ").append(escape(result.relation().startQuery())).append(" -> ").append(escape(result.relation().targetQuery()))
                    .append(" | ").append(escape(result.startTop()))
                    .append(" | ").append(escape(result.targetTop()))
                    .append(" | ").append(result.directCount())
                    .append(" | ").append(result.oneTransferCount())
                    .append(" | ").append(result.twoTransferCountText())
                    .append(" | `").append(result.classification()).append("`")
                    .append(" | `").append(result.suspectedCause()).append("`")
                    .append(" | ").append(escape(result.bestSummary()))
                    .append(" | ").append(result.elapsedMs()).append(" ms |")
                    .append(System.lineSeparator());
        }
        markdown.append(System.lineSeparator());

        markdown.append("## Offene Routing-Fragen").append(System.lineSeparator()).append(System.lineSeparator());
        markdown.append("- Leere `UNKNOWN`-Faelle muessen als naechstes mit Candidate-Pruning, TransferEdges und Kalenderfenster diagnostiziert werden.").append(System.lineSeparator());
        markdown.append("- StopArea-Top1 ist bewusst sichtbar, damit Mappingfehler sofort auffallen.").append(System.lineSeparator());
        markdown.append("- TwoTransfer-Counts sind in v0.1 bewusst konservativ und nicht als vollstaendige Netzabdeckung zu lesen.").append(System.lineSeparator());
        Files.writeString(options.markdownOutput(), markdown.toString(), StandardCharsets.UTF_8);
    }

    private static void writeCsv(Options options, List<CoverageResult> results) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("index,scenario,from_time,to_time,latest_arrival,start_query,target_query,start_area_id,start_area_name,target_area_id,target_area_name,direct_count,one_transfer_count,two_transfer_count,two_transfer_skipped,classification,suspected_cause,evidence,timed_out,elapsed_ms,best_summary")
                .append(System.lineSeparator());
        int index = 1;
        for (CoverageResult result : results) {
            StopSearchHit start = result.selectedStart();
            StopSearchHit target = result.selectedTarget();
            csv.append(index++).append(',')
                    .append(csv(result.scenario().label())).append(',')
                    .append(csv(formatSeconds(result.matrixCase().fromSeconds()))).append(',')
                    .append(csv(formatSeconds(result.matrixCase().toSeconds()))).append(',')
                    .append(csv(formatSeconds(result.matrixCase().latestArrivalSeconds()))).append(',')
                    .append(csv(result.relation().startQuery())).append(',')
                    .append(csv(result.relation().targetQuery())).append(',')
                    .append(csv(start == null ? "" : start.areaId())).append(',')
                    .append(csv(start == null ? "" : start.areaName())).append(',')
                    .append(csv(target == null ? "" : target.areaId())).append(',')
                    .append(csv(target == null ? "" : target.areaName())).append(',')
                    .append(result.directCount()).append(',')
                    .append(result.oneTransferCount()).append(',')
                    .append(result.twoTransferCount()).append(',')
                    .append(result.twoTransferSkipped()).append(',')
                    .append(csv(result.classification())).append(',')
                    .append(csv(result.suspectedCause())).append(',')
                    .append(csv(result.evidence())).append(',')
                    .append(result.timedOut()).append(',')
                    .append(result.elapsedMs()).append(',')
                    .append(csv(result.bestSummary()))
                    .append(System.lineSeparator());
        }
        Files.writeString(options.csvOutput(), csv.toString(), StandardCharsets.UTF_8);
    }

    private static List<Relation> relations() {
        return List.of(
                new Relation("Dortmund Hbf", "Gelsenkirchen Hbf"),
                new Relation("Gelsenkirchen Hbf", "Dortmund Hbf"),
                new Relation("Berlin Hbf", "Alexanderplatz"),
                new Relation("Suedkreuz", "Ostbahnhof"),
                new Relation("Koeln Deutz", "Duesseldorf Hbf"),
                new Relation("Bochum Hoentrop", "Essen Altenessen"),
                new Relation("Dortmund Hbf", "Holzwickede Landskrone"),
                new Relation("Boevinghausen Oberdelle", "Witten Bf"),
                new Relation("Essen Hbf", "Minden Bahnhof"),
                new Relation("Koenigsborn Bahnhof", "Dortmund Hbf"),
                new Relation("Castrop-Rauxel Hbf", "Dortmund Hbf"),
                new Relation("Wanne-Eickel Hbf", "Gelsenkirchen Hbf"),
                new Relation("Luenen Hbf", "Dortmund Hbf"),
                new Relation("Potsdam Hbf", "Berlin Hbf"),
                new Relation("Karl-Bonhoeffer-Nervenklinik", "Berlin Hbf"),
                new Relation("Hamburg Hbf", "Hamburg Altona"),
                new Relation("Hamburg Hbf", "Barmbek"),
                new Relation("Hamburg Altona", "Norderstedt Mitte"),
                new Relation("Bremen Hbf", "Oldenburg Hbf"),
                new Relation("Bremen Hbf", "Verden Bahnhof"),
                new Relation("Hannover Hbf", "Hildesheim Hbf"),
                new Relation("Hannover Hbf", "Celle Bahnhof"),
                new Relation("Braunschweig Hbf", "Wolfsburg Hbf"),
                new Relation("Goettingen Bahnhof", "Kassel Wilhelmshoehe"),
                new Relation("Kassel Hbf", "Kassel Wilhelmshoehe"),
                new Relation("Frankfurt Main Hbf", "Mainz Hbf"),
                new Relation("Frankfurt Main Hbf", "Wiesbaden Hbf"),
                new Relation("Frankfurt Main Hbf", "Darmstadt Hbf"),
                new Relation("Frankfurt Flughafen", "Frankfurt Main Hbf"),
                new Relation("Offenbach Marktplatz", "Frankfurt Hauptwache"),
                new Relation("Mainz Hbf", "Wiesbaden Hbf"),
                new Relation("Mannheim Hbf", "Heidelberg Hbf"),
                new Relation("Mannheim Hbf", "Ludwigshafen Mitte"),
                new Relation("Karlsruhe Hbf", "Baden-Baden Bahnhof"),
                new Relation("Karlsruhe Hbf", "Pforzheim Hbf"),
                new Relation("Stuttgart Hbf", "Esslingen Neckar"),
                new Relation("Stuttgart Hbf", "Ludwigsburg Bahnhof"),
                new Relation("Ulm Hbf", "Augsburg Hbf"),
                new Relation("Augsburg Hbf", "Muenchen Hbf"),
                new Relation("Muenchen Hbf", "Marienplatz"),
                new Relation("Muenchen Ost", "Muenchen Flughafen"),
                new Relation("Muenchen Hbf", "Freising Bahnhof"),
                new Relation("Nuernberg Hbf", "Fuerth Hbf"),
                new Relation("Nuernberg Hbf", "Erlangen Bahnhof"),
                new Relation("Wuerzburg Hbf", "Aschaffenburg Hbf"),
                new Relation("Regensburg Hbf", "Ingolstadt Hbf"),
                new Relation("Passau Hbf", "Regensburg Hbf"),
                new Relation("Leipzig Hbf", "Halle Saale Hbf"),
                new Relation("Leipzig Hbf", "Markkleeberg"),
                new Relation("Dresden Hbf", "Dresden Neustadt"),
                new Relation("Dresden Hbf", "Radebeul Ost"),
                new Relation("Chemnitz Hbf", "Zwickau Hbf"),
                new Relation("Erfurt Hbf", "Weimar Bahnhof"),
                new Relation("Jena Paradies", "Erfurt Hbf"),
                new Relation("Magdeburg Hbf", "Halle Saale Hbf"),
                new Relation("Magdeburg Hbf", "Stendal Bahnhof"),
                new Relation("Rostock Hbf", "Warnemuende"),
                new Relation("Schwerin Hbf", "Wismar Bahnhof"),
                new Relation("Kiel Hbf", "Luebeck Hbf"),
                new Relation("Kiel Hbf", "Neumuenster Bahnhof"),
                new Relation("Luebeck Hbf", "Hamburg Hbf"),
                new Relation("Flensburg Bahnhof", "Kiel Hbf"),
                new Relation("Saarbruecken Hbf", "Homburg Saar Hbf"),
                new Relation("Saarbruecken Hbf", "Trier Hbf"),
                new Relation("Trier Hbf", "Koblenz Hbf"),
                new Relation("Koblenz Hbf", "Bonn Hbf"),
                new Relation("Bonn Hbf", "Koeln Hbf"),
                new Relation("Koeln Hbf", "Bonn Hbf"),
                new Relation("Koeln Hbf", "Aachen Hbf"),
                new Relation("Koeln Hbf", "Leverkusen Mitte"),
                new Relation("Duesseldorf Hbf", "Neuss Hbf"),
                new Relation("Duesseldorf Hbf", "Wuppertal Hbf"),
                new Relation("Wuppertal Hbf", "Solingen Hbf"),
                new Relation("Essen Hbf", "Duisburg Hbf"),
                new Relation("Essen Hbf", "Bochum Hbf"),
                new Relation("Bochum Hbf", "Dortmund Hbf"),
                new Relation("Duisburg Hbf", "Oberhausen Hbf"),
                new Relation("Oberhausen Hbf", "Essen Hbf"),
                new Relation("Moers Bahnhof", "Duisburg Hbf"),
                new Relation("Krefeld Hbf", "Duesseldorf Hbf"),
                new Relation("Muenster Westf Hbf", "Hamm Westf Hbf"),
                new Relation("Muenster Westf Hbf", "Osnabrueck Hbf"),
                new Relation("Osnabrueck Hbf", "Bielefeld Hbf"),
                new Relation("Bielefeld Hbf", "Guetersloh Hbf"),
                new Relation("Bielefeld Hbf", "Bochum Hbf"),
                new Relation("Paderborn Hbf", "Bielefeld Hbf"),
                new Relation("Siegen Hbf", "Hagen Hbf"),
                new Relation("Hagen Hbf", "Dortmund Hbf"),
                new Relation("Iserlohn Bahnhof", "Dortmund Hbf"),
                new Relation("Witten Hbf", "Dortmund Hbf"),
                new Relation("Witten Bf", "Boevinghausen Oberdelle"),
                new Relation("Herne Bahnhof", "Bochum Hbf"),
                new Relation("Recklinghausen Hbf", "Dortmund Hbf"),
                new Relation("Bottrop Hbf", "Essen Hbf"),
                new Relation("Marl Mitte", "Essen Hbf"),
                new Relation("Minden Bahnhof", "Hannover Hbf"),
                new Relation("Minden Bahnhof", "Bielefeld Hbf"),
                new Relation("Berlin Ostbahnhof", "Berlin Hbf"),
                new Relation("Berlin Hbf", "Potsdam Hbf"),
                new Relation("Alexanderplatz", "Suedkreuz"),
                new Relation("Oer-Erkenschwick", "Herne Bahnhof"),
                new Relation("Xanten Bahnhof", "Duisburg Walsum"),
                new Relation("Wesel Bahnhof", "Borken Bahnhof"),
                new Relation("Dortmund Hbf", "Berlin Hbf"),
                new Relation("Stuttgart Hbf", "Augsburg Hbf"),
                new Relation("Lindau Bahnhof", "Weiler im Allgaeu"),
                new Relation("Dortmund Hbf", "Oberhausen Hbf"),
                new Relation("Dortmund Hbf", "Essen Hbf"),
                new Relation("Duisburg Walsum", "Duisburg Hbf"),
                new Relation("Wesel Bahnhof", "Duisburg Hbf"),
                new Relation("Borken Bahnhof", "Essen Hbf"),
                new Relation("Aachen Hbf", "Duesseldorf Hbf"),
                new Relation("Koblenz Hbf", "Trier Hbf"),
                new Relation("Heidelberg Hbf", "Karlsruhe Hbf"),
                new Relation("Mannheim Hbf", "Frankfurt Main Hbf"),
                new Relation("Freiburg Hbf", "Basel Bad Bf"),
                new Relation("Freiburg Hbf", "Offenburg"),
                new Relation("Offenburg", "Karlsruhe Hbf"),
                new Relation("Muenchen Hbf", "Augsburg Hbf"),
                new Relation("Muenchen Hbf", "Nuernberg Hbf"),
                new Relation("Berlin Hbf", "Berlin Ostbahnhof"),
                new Relation("Berlin Hbf", "Suedkreuz"),
                new Relation("Hamburg Hbf", "Luebeck Hbf"),
                new Relation("Bremen Hbf", "Hannover Hbf"),
                new Relation("Dortmund Hbf", "Oer-Erkenschwick")
        );
    }

    private static List<MatrixCase> matrixCases(List<Relation> relations) {
        List<MatrixCase> cases = new ArrayList<>();
        for (Relation relation : relations) {
            for (Scenario scenario : scenarios()) {
                cases.add(new MatrixCase(
                        relation,
                        scenario,
                        scenario.fromSeconds(),
                        scenario.toSeconds(),
                        scenario.latestArrivalSeconds()
                ));
            }
        }
        return List.copyOf(cases);
    }

    private static boolean relationMatchesFilters(Relation relation, List<String> filters) {
        String searchable = StopNameNormalizer.normalize(relation.startQuery() + " -> " + relation.targetQuery());
        for (String filter : filters) {
            if (searchable.contains(filter)) {
                return true;
            }
        }
        return false;
    }

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("06:07", timeSeconds("06:07"), timeSeconds("08:07"), timeSeconds("09:37")),
                new Scenario("10:13", timeSeconds("10:13"), timeSeconds("12:13"), timeSeconds("13:43")),
                new Scenario("15:17", timeSeconds("15:17"), timeSeconds("17:17"), timeSeconds("18:47")),
                new Scenario("19:23", timeSeconds("19:23"), timeSeconds("21:23"), timeSeconds("22:53"))
        );
    }

    private static String scenarioSummary() {
        return scenarios().stream()
                .map(scenario -> scenario.label()
                        + " "
                        + formatSeconds(scenario.fromSeconds())
                        + "-"
                        + formatSeconds(scenario.toSeconds()))
                .reduce((first, second) -> first + ", " + second)
                .orElse("-");
    }

    private static void configureReadOnly(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA query_only = ON");
            statement.execute("PRAGMA temp_store = MEMORY");
        }
    }

    private static List<String> queryTokens(String normalized) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split("\\s+")) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return List.copyOf(tokens);
    }

    private static String directSummary(DirectTransitLegData leg) {
        return route(leg.routeShortName(), leg.routeLongName())
                + " "
                + formatSeconds(leg.startDepartureSeconds())
                + " -> "
                + formatSeconds(leg.targetArrivalSeconds())
                + " duration="
                + leg.durationMinutes();
    }

    private static String directSummary(DirectTransitLegData leg, FamilyMember startMember, FamilyMember targetMember) {
        String access = "";
        if (startMember.accessCostMinutes() > 0 || targetMember.accessCostMinutes() > 0) {
            access = " familyAccess="
                    + startMember.accessCostMinutes()
                    + "+"
                    + targetMember.accessCostMinutes();
        }
        return directSummary(leg)
                + access
                + " areas="
                + startMember.areaId()
                + "->"
                + targetMember.areaId();
    }

    private static String oneTransferSummary(NextTransitLegData first, DirectTransitLegData second, int waitMinutes) {
        return route(first.routeShortName(), first.routeLongName())
                + " -> "
                + route(second.routeShortName(), second.routeLongName())
                + " via "
                + first.toAreaName()
                + " wait="
                + waitMinutes
                + " start="
                + formatSeconds(first.departureSeconds())
                + " arrival="
                + formatSeconds(second.targetArrivalSeconds());
    }

    private static String twoTransferSummary(
            NextTransitLegData first,
            NextTransitLegData second,
            DirectTransitLegData third,
            int firstWait,
            int secondWait
    ) {
        return route(first.routeShortName(), first.routeLongName())
                + " -> "
                + route(second.routeShortName(), second.routeLongName())
                + " -> "
                + route(third.routeShortName(), third.routeLongName())
                + " via "
                + first.toAreaName()
                + " / "
                + second.toAreaName()
                + " waits="
                + firstWait
                + "+"
                + secondWait
                + " start="
                + formatSeconds(first.departureSeconds())
                + " arrival="
                + formatSeconds(third.targetArrivalSeconds());
    }

    private static String route(String shortName, String longName) {
        if (!isBlank(shortName)) {
            return shortName;
        }
        if (!isBlank(longName)) {
            return longName;
        }
        return "-";
    }

    private static int timeSeconds(String value) {
        LocalTime time = LocalTime.parse(value.length() == 5 ? value + ":00" : value);
        return time.toSecondOfDay();
    }

    private static String formatSeconds(int seconds) {
        int hours = seconds / 3600;
        int minutes = (seconds % 3600) / 60;
        return "%02d:%02d".formatted(hours, minutes);
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static boolean isStartTargetOrBlank(String areaId, String startAreaId, String targetAreaId) {
        return isBlank(areaId) || startAreaId.equals(areaId) || targetAreaId.equals(areaId);
    }

    private static String suspectedCause(
            Relation relation,
            StopSearchHit start,
            StopSearchHit target,
            StationFamily startFamily,
            StationFamily targetFamily,
            String classification,
            boolean timedOut,
            boolean startHasDepartures,
            boolean targetHasDepartures
    ) {
        boolean startStationIntent = stationOrSelectedRailPlaceIntent(relation.startQuery(), start, startFamily);
        boolean targetStationIntent = stationOrSelectedRailPlaceIntent(relation.targetQuery(), target, targetFamily);
        String startMismatch = startStationIntent ? stopAreaMismatchDetail(relation.startQuery(), start, startFamily) : "";
        String targetMismatch = targetStationIntent ? stopAreaMismatchDetail(relation.targetQuery(), target, targetFamily) : "";
        if (classification.startsWith("FOUND")) {
            if (!isBlank(startMismatch) || !isBlank(targetMismatch)) {
                return foundMismatchCause(startMismatch, targetMismatch);
            }
            return "OK";
        }
        if (!startHasDepartures || !targetHasDepartures) {
            return "NO_ACTIVE_DEPARTURES_SELECTED_AREA";
        }
        if ("STOP_AREA_TRUE_MISMATCH".equals(startMismatch)) {
            return "START_STOP_AREA_MISMATCH";
        }
        if ("STOP_AREA_TRUE_MISMATCH".equals(targetMismatch)) {
            return "TARGET_STOP_AREA_MISMATCH";
        }
        if (!isBlank(startMismatch) || !isBlank(targetMismatch)) {
            return firstNonBlank(startMismatch, targetMismatch);
        }
        if (stopAreaAmbiguitySignal(start) || stopAreaAmbiguitySignal(target)) {
            return "STOP_AREA_AMBIGUITY";
        }
        if (timedOut) {
            return "CANDIDATE_TIMEOUT";
        }
        return "TRANSFER_DEPTH_OR_SEARCH_WINDOW";
    }

    private static String foundMismatchCause(String startMismatch, String targetMismatch) {
        if ("STOP_AREA_TRUE_MISMATCH".equals(startMismatch) || "STOP_AREA_TRUE_MISMATCH".equals(targetMismatch)) {
            return "FOUND_WITH_STOP_AREA_TRUE_MISMATCH";
        }
        if ("STOP_AREA_FAMILY_NAME_VARIANT".equals(startMismatch)
                || "STOP_AREA_FAMILY_NAME_VARIANT".equals(targetMismatch)) {
            return "FOUND_WITH_FAMILY_NAME_VARIANT";
        }
        return "FOUND_WITH_NAME_VARIANT";
    }

    private static String firstNonBlank(String first, String second) {
        return isBlank(first) ? second : first;
    }

    private static String evidence(
            StopSearchHit start,
            StopSearchHit target,
            StationFamily startFamily,
            StationFamily targetFamily,
            boolean startHasDepartures,
            boolean targetHasDepartures
    ) {
        return "startDepartures="
                + startHasDepartures
                + " targetDepartures="
                + targetHasDepartures
                + " startProfile="
                + nullToDash(start.profileClass())
                + " targetProfile="
                + nullToDash(target.profileClass())
                + " startRail="
                + start.hasRailService()
                + " targetRail="
                + target.hasRailService()
                + " startBusOnly="
                + start.busOnly()
                + " targetBusOnly="
                + target.busOnly()
                + " startFamily="
                + startFamily.summary()
                + " targetFamily="
                + targetFamily.summary();
    }

    private static boolean stationIntent(String query) {
        String normalized = StopNameNormalizer.normalize(query);
        return normalized.contains("hbf")
                || normalized.contains("hauptbahnhof")
                || normalized.contains("bahnhof")
                || normalized.endsWith(" bf")
                || normalized.contains(" ostbahnhof")
                || normalized.contains(" westbahnhof")
                || normalized.contains(" nordbahnhof")
                || normalized.contains(" suedbahnhof")
                || normalized.contains(" sudbahnhof")
                || normalized.contains("suedkreuz")
                || normalized.contains("südkreuz");
    }

    private static boolean stationOrSelectedRailPlaceIntent(String query, StopSearchHit hit, StationFamily family) {
        String normalized = StopNameNormalizer.normalize(query);
        if (stationIntent(normalized)) {
            return true;
        }
        if (!railPlaceIntent(normalized, queryTokens(normalized))) {
            return false;
        }
        String normalizedHitName = StopNameNormalizer.normalize(hit.areaName());
        String normalizedFamilyName = StopNameNormalizer.normalize(family.displayName());
        return containsRailDestinationNameSignal(normalizedHitName)
                || containsRailDestinationNameSignal(normalizedFamilyName);
    }

    private static String stopAreaMismatchDetail(String query, StopSearchHit hit, StationFamily family) {
        String normalizedQuery = StopNameNormalizer.normalize(query);
        String normalizedFamilyName = StopNameNormalizer.normalize(family.displayName());
        String normalizedHitName = StopNameNormalizer.normalize(hit.areaName());
        boolean familyTokensPresent = specificQueryTokensPresent(normalizedQuery, normalizedFamilyName);
        boolean hitTokensPresent = specificQueryTokensPresent(normalizedQuery, normalizedHitName);
        if (!familyTokensPresent) {
            if (hitTokensPresent) {
                return "STOP_AREA_FAMILY_NAME_VARIANT";
            }
            if (specificQueryTokensPresentWithAbbreviations(normalizedQuery, normalizedFamilyName)
                    || specificQueryTokensPresentWithAbbreviations(normalizedQuery, normalizedHitName)) {
                return "STOP_AREA_NAME_VARIANT";
            }
            return "STOP_AREA_TRUE_MISMATCH";
        }
        if (normalizedFamilyName.contains("zob")
                || normalizedFamilyName.contains("bus")
                || normalizedFamilyName.contains("bahnhofstr")
                || normalizedFamilyName.contains("bahnhof ost")
                || normalizedFamilyName.contains("bahnhof west")
                || normalizedFamilyName.contains("bahnhof nord")
                || normalizedFamilyName.contains("bahnhof sued")
                || normalizedFamilyName.contains("bahnhof sud")
                || normalizedFamilyName.contains("fischmarkt")
                || normalizedFamilyName.contains("kaiserstr")) {
            return hitTokensPresent ? "STOP_AREA_FAMILY_NAME_VARIANT" : "STOP_AREA_TRUE_MISMATCH";
        }
        if (railPlaceIntent(normalizedQuery, queryTokens(normalizedQuery)) && (hit.busOnly() || !hit.hasRailService())) {
            return "STOP_AREA_TRUE_MISMATCH";
        }
        if (directionalStationIntent(normalizedQuery)
                && !normalizedFamilyName.contains(normalizedQuery)
                && !normalizedHitName.contains(normalizedQuery)) {
            return "STOP_AREA_TRUE_MISMATCH";
        }
        return "";
    }

    private static boolean specificQueryTokensPresent(String normalizedQuery, String normalizedName) {
        Map<String, Integer> nameCounts = tokenCounts(normalizedName);
        for (Map.Entry<String, Integer> entry : tokenCountsWithDuplicates(normalizedQuery).entrySet()) {
            String token = entry.getKey();
            if (isGenericStationToken(token)) {
                continue;
            }
            if (nameCounts.getOrDefault(token, 0) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static boolean specificQueryTokensPresentWithAbbreviations(String normalizedQuery, String normalizedName) {
        Set<String> nameTokens = tokenCounts(normalizedName).keySet();
        for (String token : tokenCountsWithDuplicates(normalizedQuery).keySet()) {
            if (isGenericStationToken(token)) {
                continue;
            }
            boolean matched = false;
            for (String nameToken : nameTokens) {
                if (nameToken.equals(token)
                        || (token.length() >= 5 && nameToken.length() >= 4 && token.startsWith(nameToken))
                        || (nameToken.length() >= 5 && token.length() >= 4 && nameToken.startsWith(token))) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static boolean stopAreaAmbiguitySignal(StopSearchHit hit) {
        String normalizedName = StopNameNormalizer.normalize(hit.areaName());
        return hit.busOnly()
                || normalizedName.contains("zob")
                || normalizedName.contains("bus")
                || normalizedName.contains("bahnhofstr")
                || normalizedName.contains("fischmarkt")
                || normalizedName.contains("kaiserstr")
                || (!hit.hasRailService() && !"MAIN_STATION".equals(hit.profileClass()));
    }

    private static boolean directionalStationIntent(String normalizedQuery) {
        return normalizedQuery.equals("ostbahnhof")
                || normalizedQuery.equals("westbahnhof")
                || normalizedQuery.equals("nordbahnhof")
                || normalizedQuery.equals("suedbahnhof")
                || normalizedQuery.equals("sudbahnhof")
                || normalizedQuery.endsWith(" ostbahnhof")
                || normalizedQuery.endsWith(" westbahnhof")
                || normalizedQuery.endsWith(" nordbahnhof")
                || normalizedQuery.endsWith(" suedbahnhof")
                || normalizedQuery.endsWith(" sudbahnhof");
    }

    private static boolean containsRailDestinationNameSignal(String normalizedName) {
        return containsStationNameSignal(normalizedName)
                || containsToken(normalizedName, "messe")
                || containsToken(normalizedName, "koelnmesse")
                || containsToken(normalizedName, "suedkreuz")
                || containsToken(normalizedName, "sudkreuz");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    private static boolean columnExists(Connection connection, String tableName, String columnName) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
            while (resultSet.next()) {
                if (columnName.equals(resultSet.getString("name"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String nullToDash(String value) {
        return isBlank(value) ? "-" : value;
    }

    private static String escape(String value) {
        return nullToDash(value).replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String csv(String value) {
        return "\"" + nullToDash(value).replace("\"", "\"\"") + "\"";
    }

    private record Options(
            Path database,
            Path markdownOutput,
            Path csvOutput,
            LocalDate date,
            int fromSeconds,
            int toSeconds,
            int latestArrivalSeconds,
            List<String> relationFilters
    ) {
        static Options parse(String[] args) {
            Path database = Path.of("build", "ixit_gtfs_app_runtime.sqlite");
            Path markdownOutput = Path.of("..", "..", "docs", "ixit-routing-coverage-matrix-germany-v0_1.md");
            Path csvOutput = Path.of("build", "ixit-routing-coverage-matrix-germany-v0_1.csv");
            LocalDate date = DEFAULT_DATE;
            int fromSeconds = DEFAULT_FROM_SECONDS;
            int toSeconds = DEFAULT_TO_SECONDS;
            int latestArrivalSeconds = DEFAULT_LATEST_ARRIVAL_SECONDS;
            List<String> relationFilters = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--database" -> database = Path.of(requireValue(args, ++i, arg));
                    case "--markdown-output" -> markdownOutput = Path.of(requireValue(args, ++i, arg));
                    case "--csv-output" -> csvOutput = Path.of(requireValue(args, ++i, arg));
                    case "--date" -> date = LocalDate.parse(requireValue(args, ++i, arg));
                    case "--from" -> fromSeconds = timeSeconds(requireValue(args, ++i, arg));
                    case "--to" -> toSeconds = timeSeconds(requireValue(args, ++i, arg));
                    case "--latest-arrival" -> latestArrivalSeconds = timeSeconds(requireValue(args, ++i, arg));
                    case "--relation-filter" -> addRelationFilters(relationFilters, requireValue(args, ++i, arg));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            return new Options(
                    database,
                    markdownOutput,
                    csvOutput,
                    date,
                    fromSeconds,
                    toSeconds,
                    latestArrivalSeconds,
                    List.copyOf(relationFilters)
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static void addRelationFilters(List<String> filters, String value) {
            for (String part : value.split("[,;]")) {
                String normalized = StopNameNormalizer.normalize(part);
                if (!normalized.isBlank()) {
                    filters.add(normalized);
                }
            }
        }
    }

    private record Relation(String startQuery, String targetQuery) {
    }

    private record Scenario(String label, int fromSeconds, int toSeconds, int latestArrivalSeconds) {
    }

    private record MatrixCase(
            Relation relation,
            Scenario scenario,
            int fromSeconds,
            int toSeconds,
            int latestArrivalSeconds
    ) {
    }

    private record StopSearchHit(
            String areaId,
            String areaName,
            int score,
            String profileClass,
            String routeTypes,
            String lineLabels,
            boolean hasRailService,
            boolean busOnly,
            int stopCount,
            int missingSpecificTokens,
            List<String> matchedSignals
    ) {
        String summary() {
            return areaName + " / " + areaId;
        }
    }

    private record DirectScan(int count, String bestSummary) {
    }

    private record StationFamily(
            String canonicalAreaId,
            String displayName,
            String primaryAreaId,
            String requestedAreaId,
            List<FamilyMember> members
    ) {
        static StationFamily single(StopSearchHit hit) {
            FamilyMember member = FamilyMember.fromHit(hit);
            return new StationFamily(hit.areaId(), hit.areaName(), hit.areaId(), hit.areaId(), List.of(member));
        }

        List<FamilyMember> routingMembers() {
            List<FamilyMember> result = members.stream()
                    .filter(member -> member.primaryForRouting() || member.areaId().equals(requestedAreaId))
                    .sorted(Comparator
                            .comparing((FamilyMember member) -> !member.primaryForRouting())
                            .thenComparingInt(FamilyMember::accessCostMinutes)
                            .thenComparing(FamilyMember::areaId))
                    .limit(8)
                    .toList();
            if (result.isEmpty()) {
                return members.stream().limit(1).toList();
            }
            return result;
        }

        String summary() {
            return displayName
                    + "/"
                    + canonicalAreaId
                    + " requested="
                    + requestedAreaId
                    + " members="
                    + members.size()
                    + " routing="
                    + routingMembers().stream().map(FamilyMember::areaId).reduce((a, b) -> a + "+" + b).orElse("-");
        }
    }

    private record FamilyMember(
            String areaId,
            String memberRole,
            String displayRole,
            boolean primaryForSearch,
            boolean primaryForRouting,
            boolean visibleSuggestion,
            int accessCostMinutes,
            String quality,
            Integer distanceMeters,
            String profileClass,
            boolean hasRailService,
            boolean busOnly,
            String lineLabels
    ) {
        static FamilyMember fromHit(StopSearchHit hit) {
            return new FamilyMember(
                    hit.areaId(),
                    "RAW_STOP_AREA",
                    "Raw StopArea",
                    true,
                    true,
                    true,
                    0,
                    "GOOD",
                    null,
                    hit.profileClass(),
                    hit.hasRailService(),
                    hit.busOnly(),
                    hit.lineLabels()
            );
        }
    }

    private static final class MutableStopSearchHit {
        private final String areaId;
        private final String areaName;
        private final String profileClass;
        private final String routeTypes;
        private final String lineLabels;
        private final boolean hasRailService;
        private final boolean busOnly;
        private final int stopCount;
        private final Set<String> matchedTokens = new LinkedHashSet<>();
        private final List<String> matchedSignals = new ArrayList<>();
        private int score;
        private int missingSpecificTokens;

        private MutableStopSearchHit(
                String areaId,
                String areaName,
                String profileClass,
                String routeTypes,
                String lineLabels,
                boolean hasRailService,
                boolean busOnly,
                int stopCount
        ) {
            this.areaId = areaId;
            this.areaName = areaName;
            this.profileClass = profileClass;
            this.routeTypes = routeTypes;
            this.lineLabels = lineLabels;
            this.hasRailService = hasRailService;
            this.busOnly = busOnly;
            this.stopCount = stopCount;
        }

        private StopSearchHit toHit() {
            return new StopSearchHit(
                    areaId,
                    areaName,
                    score,
                    profileClass,
                    routeTypes,
                    lineLabels,
                    hasRailService,
                    busOnly,
                    stopCount,
                    missingSpecificTokens,
                    List.copyOf(matchedSignals)
            );
        }
    }

    private record CoverageResult(
            MatrixCase matrixCase,
            List<StopSearchHit> startHits,
            List<StopSearchHit> targetHits,
            StopSearchHit selectedStart,
            StopSearchHit selectedTarget,
            int directCount,
            int oneTransferCount,
            int twoTransferCount,
            boolean twoTransferSkipped,
            String bestSummary,
            String classification,
            String suspectedCause,
            String evidence,
            boolean timedOut,
            long elapsedMs
    ) {
        static CoverageResult unresolved(
                MatrixCase matrixCase,
                List<StopSearchHit> startHits,
                List<StopSearchHit> targetHits,
                long elapsedMs
        ) {
            return new CoverageResult(
                    matrixCase,
                    startHits,
                    targetHits,
                    startHits.isEmpty() ? null : startHits.get(0),
                    targetHits.isEmpty() ? null : targetHits.get(0),
                    0,
                    0,
                    0,
                    true,
                    "",
                    "NOT_FOUND_STOP_AREA_MISMATCH",
                    "STOP_AREA_UNRESOLVED",
                    "startHits=" + startHits.size() + " targetHits=" + targetHits.size(),
                    false,
                    elapsedMs
            );
        }

        Relation relation() {
            return matrixCase.relation();
        }

        Scenario scenario() {
            return matrixCase.scenario();
        }

        String scenarioSummary() {
            return scenario().label()
                    + " "
                    + formatSeconds(matrixCase.fromSeconds())
                    + "-"
                    + formatSeconds(matrixCase.toSeconds());
        }

        String startTop() {
            return selectedStart == null ? "" : selectedStart.summary();
        }

        String targetTop() {
            return selectedTarget == null ? "" : selectedTarget.summary();
        }

        String twoTransferCountText() {
            return twoTransferSkipped ? "-" : Integer.toString(twoTransferCount);
        }
    }

    private record OneTransferScan(int count, String bestSummary, boolean timedOut) {
    }

    private record TwoTransferScan(int count, String bestSummary, boolean timedOut, boolean skipped) {
        static TwoTransferScan skippedResult() {
            return new TwoTransferScan(0, "", false, true);
        }
    }
}
