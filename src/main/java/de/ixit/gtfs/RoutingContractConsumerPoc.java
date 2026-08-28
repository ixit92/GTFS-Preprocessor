package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import de.ixit.gtfs.RoutingContractConsumerReport.AreaEvidence;
import de.ixit.gtfs.RoutingContractConsumerReport.ContractEvidence;
import de.ixit.gtfs.RoutingContractConsumerReport.MemberEvidence;
import de.ixit.gtfs.RoutingContractConsumerReport.ValidatedLeg;
import de.ixit.gtfs.SqliteDirectConnectionFinder.DirectConnectionData;
import de.ixit.gtfs.TransitDataAccess.ResolvedStopAreaData;
import de.ixit.gtfs.TransitDataAccess.TripMetadataData;
import de.ixit.gtfs.TransitDataAccess.TripStopTimeData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoutingContractConsumerPoc {
    public static final String CONSUMER_VERSION = "0.8";
    public static final List<String> ACCEPTED_CONTRACT_VERSIONS = List.of(SqliteContract.CONTRACT_VERSION);
    public static final String DECISION_SCOPE = "direct_trip_validation_only_not_route_planning";

    private static final int SERVICE_DAY_BOUNDARY_SECONDS = 24 * 60 * 60;
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final List<String> REQUIRED_TABLES = List.of(
            "ixit_metadata",
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "calendar",
            "calendar_dates"
    );

    private RoutingContractConsumerPoc() {
    }

    public static RoutingContractConsumerReport inspect(
            Path database,
            LocalDate serviceDate,
            String startAreaId,
            String targetAreaId,
            int fromSeconds,
            int toSeconds,
            int limit
    ) throws SQLException {
        validateArguments(database, serviceDate, startAreaId, targetAreaId, fromSeconds, toSeconds, limit);
        ContractEvidence contract = readAndValidateContract(database);
        List<String> failures = new ArrayList<>();
        Map<String, String> checks = baseChecks(contract);

        try (SqliteTransitDataAccess dataAccess = new SqliteTransitDataAccess(database)) {
            SqliteDirectConnectionFinder finder = new SqliteDirectConnectionFinder(dataAccess);
            var result = finder.findDirectConnections(
                    serviceDate,
                    startAreaId,
                    targetAreaId,
                    fromSeconds,
                    toSeconds,
                    limit,
                    Math.max(limit, 1_000)
            );

            AreaEvidence startArea = areaEvidence(result.startArea());
            AreaEvidence targetArea = areaEvidence(result.targetArea());
            Set<String> startMemberIds = memberIds(startArea);
            Set<String> targetMemberIds = memberIds(targetArea);
            if (startMemberIds.isEmpty() || targetMemberIds.isEmpty()) {
                failures.add("Selected StopAreas must resolve to at least one concrete stop_id");
                checks.put("area_to_stop_members", "FAIL");
            } else {
                checks.put("area_to_stop_members", "PASS");
            }

            List<ValidatedLeg> validatedLegs = new ArrayList<>();
            for (DirectConnectionData connection : result.connections()) {
                validatedLegs.add(validateLeg(
                        dataAccess,
                        serviceDate,
                        startAreaId,
                        targetAreaId,
                        startMemberIds,
                        targetMemberIds,
                        connection,
                        failures
                ));
            }

            if (validatedLegs.isEmpty()) {
                failures.add("No concrete trip matched both StopAreas, service date and departure window");
            }
            boolean allLegsValid = !validatedLegs.isEmpty() && validatedLegs.stream().allMatch(ValidatedLeg::valid);
            checks.put("concrete_trip_stop_time_validation", allLegsValid ? "PASS" : "FAIL");
            checks.put("service_day_validation", allLegsValid ? "PASS" : "FAIL");

            boolean overflowObserved = validatedLegs.stream().anyMatch(ValidatedLeg::overflowTime);
            checks.put(
                    "over_24h_time_model",
                    overflowObserved ? "PASS_OBSERVED" : "PASS_SUPPORTED_NOT_OBSERVED"
            );
            checks.put("search_tokens_scope", "IGNORED_SEARCH_ONLY");
            checks.put("derived_analysis_scope", "IGNORED_HUBPROFILES_ROUTEAXIS_TRANSFERRULES");
            checks.put("decision_scope", "VALIDATION_ONLY_NOT_ROUTING");

            return new RoutingContractConsumerReport(
                    CONSUMER_VERSION,
                    Instant.now().toString(),
                    database.toAbsolutePath().normalize().toString(),
                    contract,
                    startArea,
                    targetArea,
                    serviceDate.toString(),
                    fromSeconds,
                    toSeconds,
                    result.activeServiceCount(),
                    result.directCandidateCount(),
                    overflowObserved,
                    List.copyOf(validatedLegs),
                    Map.copyOf(checks),
                    List.copyOf(failures),
                    DECISION_SCOPE,
                    failures.isEmpty() && allLegsValid
            );
        }
    }

    public static void write(Path output, RoutingContractConsumerReport report) throws IOException {
        Path normalized = output.toAbsolutePath().normalize();
        if (normalized.getParent() != null) {
            Files.createDirectories(normalized.getParent());
        }
        JSON.writeValue(normalized.toFile(), report);
    }

    private static ValidatedLeg validateLeg(
            TransitDataAccess dataAccess,
            LocalDate serviceDate,
            String startAreaId,
            String targetAreaId,
            Set<String> startMemberIds,
            Set<String> targetMemberIds,
            DirectConnectionData connection,
            List<String> failures
    ) throws SQLException {
        TripMetadataData metadata = dataAccess.getTripMetadata(connection.tripId());
        boolean metadataValid = metadata != null
                && Objects.equals(metadata.routeId(), connection.routeId())
                && Objects.equals(metadata.serviceId(), connection.serviceId());

        var service = dataAccess.getServiceActiveData(connection.serviceId(), serviceDate);
        boolean serviceDayValid = service.active();
        boolean membershipValid = startMemberIds.contains(connection.startStopId())
                && targetMemberIds.contains(connection.targetStopId());

        List<TripStopTimeData> stopTimes = dataAccess.getTripStopTimes(connection.tripId());
        TripStopTimeData startTime = findStopTime(
                stopTimes,
                connection.startStopId(),
                connection.startSequence()
        );
        TripStopTimeData targetTime = findStopTime(
                stopTimes,
                connection.targetStopId(),
                connection.targetSequence()
        );
        boolean stopTimePathValid = startTime != null
                && targetTime != null
                && Objects.equals(startTime.areaId(), startAreaId)
                && Objects.equals(targetTime.areaId(), targetAreaId)
                && startTime.departureSeconds() == connection.startDepartureSeconds()
                && targetTime.arrivalSeconds() == connection.targetArrivalSeconds()
                && targetTime.stopSequence() > startTime.stopSequence()
                && targetTime.arrivalSeconds() >= startTime.departureSeconds();

        boolean valid = metadataValid && serviceDayValid && membershipValid && stopTimePathValid;
        if (!valid) {
            List<String> legFailures = new ArrayList<>();
            if (!metadataValid) {
                legFailures.add("trip metadata");
            }
            if (!serviceDayValid) {
                legFailures.add("service day");
            }
            if (!membershipValid) {
                legFailures.add("stop membership");
            }
            if (!stopTimePathValid) {
                legFailures.add("stop_times path");
            }
            failures.add("Trip " + connection.tripId() + " failed: " + String.join(", ", legFailures));
        }

        boolean overflow = connection.startDepartureSeconds() >= SERVICE_DAY_BOUNDARY_SECONDS
                || connection.targetArrivalSeconds() >= SERVICE_DAY_BOUNDARY_SECONDS;
        return new ValidatedLeg(
                connection.tripId(),
                connection.routeId(),
                connection.serviceId(),
                connection.startStopId(),
                connection.targetStopId(),
                connection.startSequence(),
                connection.targetSequence(),
                connection.startDepartureSeconds(),
                connection.targetArrivalSeconds(),
                formatSeconds(connection.startDepartureSeconds()),
                formatSeconds(connection.targetArrivalSeconds()),
                service.reason(),
                metadataValid,
                serviceDayValid,
                membershipValid,
                stopTimePathValid,
                overflow,
                valid
        );
    }

    private static TripStopTimeData findStopTime(List<TripStopTimeData> stopTimes, String stopId, int sequence) {
        return stopTimes.stream()
                .filter(stopTime -> Objects.equals(stopTime.stopId(), stopId)
                        && stopTime.stopSequence() == sequence)
                .findFirst()
                .orElse(null);
    }

    private static ContractEvidence readAndValidateContract(Path database) throws SQLException {
        Map<String, String> metadata = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath())) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            for (String table : REQUIRED_TABLES) {
                if (!tableExists(connection, table)) {
                    errors.add("required table missing: " + table);
                }
            }
            if (errors.isEmpty()) {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT key, value FROM ixit_metadata")) {
                    try (ResultSet resultSet = statement.executeQuery()) {
                        while (resultSet.next()) {
                            metadata.put(resultSet.getString("key"), resultSet.getString("value"));
                        }
                    }
                }
            }
        }

        require(metadata, "contract_name", SqliteContract.CONTRACT_NAME, errors);
        requireAcceptedVersion(metadata.get("contract_version"), errors);
        requireNonBlank(metadata, "preprocessor_version", errors);
        require(metadata, "time_model", SqliteContract.TIME_MODEL, errors);
        require(metadata, "stop_id_policy", SqliteContract.STOP_ID_POLICY, errors);
        require(metadata, "area_id_policy", SqliteContract.AREA_ID_POLICY, errors);
        require(metadata, "search_tokens_policy", SqliteContract.SEARCH_TOKENS_POLICY, errors);
        require(metadata, "service_day_resolution_policy", SqliteContract.SERVICE_DAY_RESOLUTION_POLICY, errors);
        require(metadata, "service_day_timezone_policy", SqliteContract.SERVICE_DAY_TIMEZONE_POLICY, errors);
        require(metadata, "service_day_time_overflow_policy", SqliteContract.SERVICE_DAY_TIME_OVERFLOW_POLICY, errors);
        if (!errors.isEmpty()) {
            throw new RoutingContractViolationException(String.join("; ", errors));
        }

        return new ContractEvidence(
                metadata.get("contract_name"),
                metadata.get("contract_version"),
                ACCEPTED_CONTRACT_VERSIONS,
                metadata.get("preprocessor_version"),
                metadata.get("time_model"),
                metadata.get("stop_id_policy"),
                metadata.get("area_id_policy"),
                metadata.get("search_tokens_policy"),
                metadata.get("service_day_resolution_policy"),
                metadata.get("service_day_timezone_policy"),
                metadata.get("service_day_time_overflow_policy")
        );
    }

    private static Map<String, String> baseChecks(ContractEvidence contract) {
        Map<String, String> checks = new LinkedHashMap<>();
        checks.put("contract_version", "PASS_" + contract.contractVersion());
        checks.put("stop_id_policy", "PASS_ORIGINAL_GTFS_ID");
        checks.put("area_id_policy", "PASS_IXIT_STOP_AREA");
        checks.put("time_model", "PASS_SECONDS_SINCE_SERVICE_DAY_START");
        return checks;
    }

    private static AreaEvidence areaEvidence(ResolvedStopAreaData area) {
        return new AreaEvidence(
                area.areaId(),
                area.displayName(),
                area.members().stream()
                        .map(member -> new MemberEvidence(member.stopId(), member.stopName()))
                        .toList()
        );
    }

    private static Set<String> memberIds(AreaEvidence area) {
        return area.concreteMembers().stream()
                .map(MemberEvidence::stopId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean tableExists(Connection connection, String tableName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?
                """)) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void require(Map<String, String> metadata, String key, String expected, List<String> errors) {
        String actual = metadata.get(key);
        if (!Objects.equals(expected, actual)) {
            errors.add(key + " must be " + expected + " but was " + (actual == null ? "<missing>" : actual));
        }
    }

    private static void requireNonBlank(Map<String, String> metadata, String key, List<String> errors) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) {
            errors.add(key + " must be set");
        }
    }

    private static void requireAcceptedVersion(String version, List<String> errors) {
        if (!ACCEPTED_CONTRACT_VERSIONS.contains(version)) {
            errors.add("contract_version " + (version == null ? "<missing>" : version)
                    + " is unsupported; accepted " + ACCEPTED_CONTRACT_VERSIONS);
        }
    }

    private static void validateArguments(
            Path database,
            LocalDate serviceDate,
            String startAreaId,
            String targetAreaId,
            int fromSeconds,
            int toSeconds,
            int limit
    ) {
        if (database == null || !Files.isRegularFile(database)) {
            throw new IllegalArgumentException("SQLite database not found: " + database);
        }
        if (serviceDate == null) {
            throw new IllegalArgumentException("serviceDate must not be null");
        }
        if (isBlank(startAreaId) || isBlank(targetAreaId)) {
            throw new IllegalArgumentException("startAreaId and targetAreaId must not be blank");
        }
        if (fromSeconds < 0 || toSeconds < fromSeconds) {
            throw new IllegalArgumentException("invalid departure window");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
    }

    private static String formatSeconds(int value) {
        int hours = value / 3_600;
        int minutes = value % 3_600 / 60;
        int seconds = value % 60;
        return "%02d:%02d:%02d".formatted(hours, minutes, seconds);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public static final class RoutingContractViolationException extends IllegalArgumentException {
        public RoutingContractViolationException(String message) {
            super("Routing Contract Consumer rejected SQLite: " + message);
        }
    }
}
