package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class MobileArtifactPackager {
    public static final String MANIFEST_VERSION = "0.1";
    public static final String PACKAGER_VERSION = "0.9.1";
    public static final String ARTIFACT_PROFILE = "DIRECT_TRIP_CORRIDOR_V1";
    public static final String DATABASE_FILE = "ixit-gtfs-contract-0.7.sqlite";
    public static final String MANIFEST_FILE = "ixit-mobile-artifact-manifest-v0.1.json";
    public static final String SIGNATURE_FILE = "ixit-mobile-artifact-manifest-v0.1.sig";
    public static final String SEARCHABLE_MANIFEST_VERSION = "0.2";
    public static final String SEARCHABLE_PACKAGER_VERSION = "0.9.3";
    public static final String SEARCHABLE_ARTIFACT_PROFILE = "SEARCHABLE_DIRECT_TRIP_CORRIDOR_V2";
    public static final String SEARCHABLE_MANIFEST_FILE = "ixit-mobile-artifact-manifest-v0.2.json";
    public static final String SEARCHABLE_SIGNATURE_FILE = "ixit-mobile-artifact-manifest-v0.2.sig";
    public static final long DEFAULT_MAX_BYTES = 768L * 1024 * 1024;

    private static final List<String> ROUTING_TABLES = List.of(
            "ixit_metadata",
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "calendar",
            "calendar_dates",
            "service_calendar_summary"
    );
    private static final List<String> SEARCHABLE_TABLES = List.of(
            "ixit_metadata",
            "stops",
            "stop_areas",
            "stop_area_members",
            "routes",
            "trips",
            "stop_times",
            "calendar",
            "calendar_dates",
            "service_calendar_summary",
            "stop_search_tokens",
            "stop_area_aliases",
            "canonical_stop_area_names",
            "stop_area_display_names"
    );
    private static final Map<String, Set<String>> SEARCHABLE_REQUIRED_COLUMNS = Map.ofEntries(
            Map.entry("ixit_metadata", Set.of("key", "value")),
            Map.entry("stops", Set.of("stop_id", "stop_name")),
            Map.entry("stop_areas", Set.of("area_id", "area_name", "stop_count")),
            Map.entry("stop_area_members", Set.of("area_id", "stop_id")),
            Map.entry("routes", Set.of("route_id")),
            Map.entry("trips", Set.of("trip_id", "route_id", "service_id")),
            Map.entry("stop_times", Set.of(
                    "trip_id", "stop_id", "stop_sequence", "arrival_seconds", "departure_seconds"
            )),
            Map.entry("calendar", Set.of("service_id")),
            Map.entry("calendar_dates", Set.of("service_id", "date", "exception_type")),
            Map.entry("service_calendar_summary", Set.of(
                    "service_id", "has_calendar", "weekday_mask", "start_date", "end_date",
                    "service_timezone", "trip_count"
            )),
            Map.entry("stop_search_tokens", Set.of(
                    "stop_id", "area_id", "token", "token_type", "source"
            )),
            Map.entry("stop_area_aliases", Set.of(
                    "area_id", "alias", "alias_normalized", "alias_type", "source", "priority"
            )),
            Map.entry("canonical_stop_area_names", Set.of(
                    "canonical_area_id", "display_name", "display_name_normalized", "city_name",
                    "station_name"
            )),
            Map.entry("stop_area_display_names", Set.of(
                    "area_id", "canonical_area_id", "public_display_name",
                    "public_display_name_normalized", "public_stop_name", "public_city_name",
                    "display_quality", "source"
            ))
    );
    private static final ArtifactProfile ROUTING_PROFILE = new ArtifactProfile(
            MANIFEST_VERSION,
            PACKAGER_VERSION,
            ARTIFACT_PROFILE,
            MANIFEST_FILE,
            SIGNATURE_FILE,
            ROUTING_TABLES,
            false
    );
    private static final ArtifactProfile SEARCHABLE_PROFILE = new ArtifactProfile(
            SEARCHABLE_MANIFEST_VERSION,
            SEARCHABLE_PACKAGER_VERSION,
            SEARCHABLE_ARTIFACT_PROFILE,
            SEARCHABLE_MANIFEST_FILE,
            SEARCHABLE_SIGNATURE_FILE,
            SEARCHABLE_TABLES,
            true
    );
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private MobileArtifactPackager() {
    }

    public static MobileArtifactManifest packageArtifact(
            Path sourceDatabase,
            Path outputDirectory,
            List<String> seedAreaIds,
            String artifactId,
            long maximumBytes,
            PrivateKey privateKey,
            PublicKey publicKey
    ) throws IOException, SQLException, GeneralSecurityException {
        return packageArtifact(
                sourceDatabase,
                outputDirectory,
                seedAreaIds,
                artifactId,
                maximumBytes,
                privateKey,
                publicKey,
                ROUTING_PROFILE
        );
    }

    public static MobileArtifactManifest packageSearchableArtifact(
            Path sourceDatabase,
            Path outputDirectory,
            List<String> seedAreaIds,
            String artifactId,
            long maximumBytes,
            PrivateKey privateKey,
            PublicKey publicKey
    ) throws IOException, SQLException, GeneralSecurityException {
        return packageArtifact(
                sourceDatabase,
                outputDirectory,
                seedAreaIds,
                artifactId,
                maximumBytes,
                privateKey,
                publicKey,
                SEARCHABLE_PROFILE
        );
    }

    private static MobileArtifactManifest packageArtifact(
            Path sourceDatabase,
            Path outputDirectory,
            List<String> seedAreaIds,
            String artifactId,
            long maximumBytes,
            PrivateKey privateKey,
            PublicKey publicKey,
            ArtifactProfile profile
    ) throws IOException, SQLException, GeneralSecurityException {
        Path source = requireSource(sourceDatabase);
        Path output = outputDirectory.toAbsolutePath().normalize();
        List<String> seeds = normalizeSeeds(seedAreaIds);
        validateArtifactId(artifactId);
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Output directory already exists: " + output);
        }
        if (privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("A signing private key and matching public key are required");
        }
        verifyKeyPair(privateKey, publicKey);

        SourceContract sourceContract = validateSourceContract(source, seeds, profile);
        String sourceSha256 = BuildIdentity.sha256File(source);
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Output directory must have a parent: " + output);
        }
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + output.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectory(staging);

        boolean published = false;
        try {
            Path mobileDatabase = staging.resolve(DATABASE_FILE);
            Map<String, Long> rowCounts = buildReducedDatabase(
                    source,
                    mobileDatabase,
                    seeds,
                    sourceSha256,
                    artifactId,
                    profile
            );
            long databaseBytes = Files.size(mobileDatabase);
            if (databaseBytes > maximumBytes) {
                throw new IllegalStateException("Reduced mobile database exceeds limit: "
                        + databaseBytes + " > " + maximumBytes);
            }
            String databaseSha256 = BuildIdentity.sha256File(mobileDatabase);
            MobileArtifactManifest manifest = new MobileArtifactManifest(
                    profile.manifestVersion(),
                    profile.packagerVersion(),
                    profile.artifactProfile(),
                    artifactId,
                    Instant.now().toString(),
                    DATABASE_FILE,
                    databaseSha256,
                    databaseBytes,
                    sourceSha256,
                    sourceContract.contractVersion(),
                    sourceContract.preprocessorVersion(),
                    sourceContract.buildIdentitySha256(),
                    seeds,
                    rowCounts,
                    MobileArtifactCrypto.SIGNATURE_ALGORITHM,
                    MobileArtifactCrypto.keyId(publicKey)
            );
            byte[] manifestBytes = JSON.writeValueAsBytes(manifest);
            Files.write(staging.resolve(profile.manifestFile()), manifestBytes);
            byte[] signature = MobileArtifactCrypto.sign(manifestBytes, privateKey);
            Files.writeString(
                    staging.resolve(profile.signatureFile()),
                    java.util.Base64.getEncoder().encodeToString(signature) + System.lineSeparator(),
                    StandardCharsets.US_ASCII
            );
            verifyPackage(staging, publicKey, maximumBytes, profile);
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic package publication is not supported for " + output, exception);
            }
            published = true;
            return manifest;
        } finally {
            if (!published) {
                deleteStagingTree(staging, parent);
            }
        }
    }

    public static MobileArtifactManifest verifyPackage(
            Path packageDirectory,
            PublicKey publicKey,
            long maximumBytes
    ) throws IOException, GeneralSecurityException, SQLException {
        return verifyPackage(packageDirectory, publicKey, maximumBytes, ROUTING_PROFILE);
    }

    public static MobileArtifactManifest verifySearchablePackage(
            Path packageDirectory,
            PublicKey publicKey,
            long maximumBytes
    ) throws IOException, GeneralSecurityException, SQLException {
        return verifyPackage(packageDirectory, publicKey, maximumBytes, SEARCHABLE_PROFILE);
    }

    private static MobileArtifactManifest verifyPackage(
            Path packageDirectory,
            PublicKey publicKey,
            long maximumBytes,
            ArtifactProfile profile
    ) throws IOException, GeneralSecurityException, SQLException {
        Path directory = packageDirectory.toAbsolutePath().normalize();
        Path manifestPath = requireRegularPackageFile(directory, profile.manifestFile());
        Path signaturePath = requireRegularPackageFile(directory, profile.signatureFile());
        byte[] manifestBytes = Files.readAllBytes(manifestPath);
        if (manifestBytes.length < 1 || manifestBytes.length > 64 * 1024) {
            throw new IllegalArgumentException("Mobile manifest must be between 1 byte and 64 KiB");
        }
        if (Files.size(signaturePath) < 1 || Files.size(signaturePath) > 8 * 1024) {
            throw new IllegalArgumentException("Mobile manifest signature file is invalid");
        }
        byte[] signature;
        try {
            signature = java.util.Base64.getDecoder().decode(
                    Files.readString(signaturePath, StandardCharsets.US_ASCII).trim()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid manifest signature encoding", exception);
        }
        if (!MobileArtifactCrypto.verify(manifestBytes, signature, publicKey)) {
            throw new IllegalArgumentException("Mobile manifest signature verification failed");
        }
        MobileArtifactManifest manifest = JSON.readValue(manifestBytes, MobileArtifactManifest.class);
        validateManifest(manifest, publicKey, maximumBytes, profile);
        Path database = requireRegularPackageFile(directory, manifest.databaseFile());
        long actualBytes = Files.size(database);
        if (actualBytes != manifest.databaseBytes() || actualBytes > maximumBytes) {
            throw new IllegalArgumentException("Mobile database size does not match manifest or limit");
        }
        String actualSha256 = BuildIdentity.sha256File(database);
        if (!actualSha256.equals(manifest.databaseSha256())) {
            throw new IllegalArgumentException("Mobile database SHA-256 does not match manifest");
        }
        validateReducedContract(database, manifest, profile);
        return manifest;
    }

    private static Map<String, Long> buildReducedDatabase(
            Path source,
            Path output,
            List<String> seeds,
            String sourceSha256,
            String artifactId,
            ArtifactProfile profile
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + output)) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = OFF");
                statement.execute("PRAGMA journal_mode = DELETE");
                statement.execute("ATTACH DATABASE '" + sqliteLiteral(source) + "' AS source");
                statement.execute("CREATE TEMP TABLE selected_seed_areas(area_id TEXT PRIMARY KEY)");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO selected_seed_areas(area_id) VALUES (?)")) {
                for (String seed : seeds) {
                    insert.setString(1, seed);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("""
                        CREATE TEMP TABLE selected_trips AS
                        SELECT DISTINCT times.trip_id
                        FROM source.stop_times times
                        JOIN source.stop_area_members members ON members.stop_id = times.stop_id
                        JOIN selected_seed_areas seeds ON seeds.area_id = members.area_id
                        """);
                statement.execute("CREATE UNIQUE INDEX selected_trips_pk ON selected_trips(trip_id)");
                statement.execute("""
                        CREATE TEMP TABLE selected_trip_stops AS
                        SELECT DISTINCT times.stop_id
                        FROM source.stop_times times
                        JOIN selected_trips trips ON trips.trip_id = times.trip_id
                        """);
                statement.execute("CREATE UNIQUE INDEX selected_trip_stops_pk "
                        + "ON selected_trip_stops(stop_id)");
                statement.execute("""
                        CREATE TEMP TABLE selected_stops AS
                        SELECT stop_id FROM selected_trip_stops
                        UNION
                        SELECT source_stops.parent_station
                        FROM source.stops source_stops
                        JOIN selected_trip_stops trip_stops ON trip_stops.stop_id = source_stops.stop_id
                        WHERE source_stops.parent_station IS NOT NULL
                          AND TRIM(source_stops.parent_station) <> ''
                        """);
                statement.execute("CREATE UNIQUE INDEX selected_stops_pk ON selected_stops(stop_id)");
                statement.execute("""
                        CREATE TEMP TABLE selected_areas AS
                        SELECT DISTINCT members.area_id
                        FROM source.stop_area_members members
                        JOIN selected_stops stops ON stops.stop_id = members.stop_id
                        """);
                statement.execute("CREATE UNIQUE INDEX selected_areas_pk ON selected_areas(area_id)");
                if (profile.searchable()) {
                    statement.execute("INSERT OR IGNORE INTO selected_stops(stop_id) "
                            + "SELECT members.stop_id FROM source.stop_area_members members "
                            + "JOIN selected_areas areas ON areas.area_id = members.area_id");
                    statement.execute("INSERT OR IGNORE INTO selected_stops(stop_id) "
                            + "SELECT source_stops.parent_station FROM source.stops source_stops "
                            + "JOIN selected_stops stops ON stops.stop_id = source_stops.stop_id "
                            + "WHERE source_stops.parent_station IS NOT NULL "
                            + "AND TRIM(source_stops.parent_station) <> ''");
                }
                for (String table : profile.tables()) {
                    statement.execute(sourceCreateSql(connection, table));
                }
                statement.execute("INSERT INTO ixit_metadata SELECT * FROM source.ixit_metadata");
                statement.execute("INSERT INTO stops SELECT source_rows.* FROM source.stops source_rows "
                        + "JOIN selected_stops ON selected_stops.stop_id = source_rows.stop_id");
                statement.execute("INSERT INTO stop_areas SELECT source_rows.* FROM source.stop_areas source_rows "
                        + "JOIN selected_areas ON selected_areas.area_id = source_rows.area_id");
                statement.execute("INSERT INTO stop_area_members SELECT source_rows.* "
                        + "FROM source.stop_area_members source_rows "
                        + "JOIN selected_stops ON selected_stops.stop_id = source_rows.stop_id "
                        + "JOIN selected_areas ON selected_areas.area_id = source_rows.area_id");
                statement.execute("UPDATE stop_areas SET stop_count = ("
                        + "SELECT COUNT(*) FROM stop_area_members members "
                        + "WHERE members.area_id = stop_areas.area_id)");
                statement.execute("INSERT INTO trips SELECT source_rows.* FROM source.trips source_rows "
                        + "JOIN selected_trips ON selected_trips.trip_id = source_rows.trip_id");
                statement.execute("INSERT INTO routes SELECT DISTINCT source_rows.* FROM source.routes source_rows "
                        + "JOIN trips ON trips.route_id = source_rows.route_id");
                statement.execute("INSERT INTO stop_times SELECT source_rows.* FROM source.stop_times source_rows "
                        + "JOIN selected_trips ON selected_trips.trip_id = source_rows.trip_id");
                statement.execute("INSERT INTO calendar SELECT source_rows.* FROM source.calendar source_rows "
                        + "JOIN (SELECT DISTINCT service_id FROM trips) services "
                        + "ON services.service_id = source_rows.service_id");
                statement.execute("INSERT INTO calendar_dates SELECT source_rows.* "
                        + "FROM source.calendar_dates source_rows "
                        + "JOIN (SELECT DISTINCT service_id FROM trips) services "
                        + "ON services.service_id = source_rows.service_id");
                statement.execute("INSERT INTO service_calendar_summary SELECT source_rows.* "
                        + "FROM source.service_calendar_summary source_rows "
                        + "JOIN (SELECT DISTINCT service_id FROM trips) services "
                        + "ON services.service_id = source_rows.service_id");
                statement.execute("UPDATE service_calendar_summary SET trip_count = ("
                        + "SELECT COUNT(*) FROM trips "
                        + "WHERE trips.service_id = service_calendar_summary.service_id)");
                if (profile.searchable()) {
                    statement.execute("INSERT INTO stop_search_tokens SELECT source_rows.* "
                            + "FROM source.stop_search_tokens source_rows "
                            + "JOIN selected_areas areas ON areas.area_id = source_rows.area_id "
                            + "WHERE source_rows.stop_id IS NULL OR EXISTS ("
                            + "SELECT 1 FROM selected_stops stops "
                            + "WHERE stops.stop_id = source_rows.stop_id)");
                    statement.execute("INSERT INTO stop_area_aliases SELECT source_rows.* "
                            + "FROM source.stop_area_aliases source_rows "
                            + "JOIN selected_areas areas ON areas.area_id = source_rows.area_id");
                    statement.execute("INSERT INTO stop_area_display_names SELECT source_rows.* "
                            + "FROM source.stop_area_display_names source_rows "
                            + "JOIN selected_areas areas ON areas.area_id = source_rows.area_id");
                    statement.execute("INSERT INTO canonical_stop_area_names SELECT source_rows.* "
                            + "FROM source.canonical_stop_area_names source_rows "
                            + "JOIN (SELECT DISTINCT canonical_area_id FROM stop_area_display_names) selected "
                            + "ON selected.canonical_area_id = source_rows.canonical_area_id");
                }
                upsertMetadata(connection, "mobile_artifact_profile", profile.artifactProfile());
                upsertMetadata(connection, "mobile_packager_version", profile.packagerVersion());
                upsertMetadata(connection, "mobile_artifact_id", artifactId);
                upsertMetadata(connection, "mobile_parent_database_sha256", sourceSha256);
                upsertMetadata(connection, "mobile_seed_area_count", Integer.toString(seeds.size()));
                if (profile.searchable()) {
                    upsertMetadata(connection, "mobile_search_data_policy",
                            "display_and_tokens_search_only_not_routing");
                    upsertMetadata(connection, "mobile_update_contract_version",
                            MobileArtifactUpdateContract.DESCRIPTOR_VERSION);
                }
                statement.execute("CREATE INDEX idx_stop_area_members_area_id ON stop_area_members(area_id)");
                statement.execute("CREATE INDEX idx_stop_area_members_stop_id ON stop_area_members(stop_id)");
                statement.execute("CREATE INDEX idx_trips_service_id ON trips(service_id)");
                statement.execute("CREATE INDEX idx_stop_times_trip_sequence ON stop_times(trip_id, stop_sequence)");
                statement.execute("CREATE INDEX idx_stop_times_stop_departure "
                        + "ON stop_times(stop_id, departure_seconds)");
                statement.execute("CREATE INDEX idx_calendar_dates_service_date "
                        + "ON calendar_dates(service_id, date)");
                if (profile.searchable()) {
                    statement.execute("CREATE INDEX idx_stop_search_tokens_token "
                            + "ON stop_search_tokens(token)");
                    statement.execute("CREATE INDEX idx_stop_search_tokens_stop_id "
                            + "ON stop_search_tokens(stop_id)");
                    statement.execute("CREATE INDEX idx_stop_search_tokens_area_id "
                            + "ON stop_search_tokens(area_id)");
                    statement.execute("CREATE INDEX idx_stop_search_tokens_token_area "
                            + "ON stop_search_tokens(token, area_id)");
                    statement.execute("CREATE INDEX idx_stop_area_aliases_area_normalized "
                            + "ON stop_area_aliases(area_id, alias_normalized)");
                    statement.execute("CREATE INDEX idx_canonical_stop_area_names_normalized "
                            + "ON canonical_stop_area_names(display_name_normalized)");
                    statement.execute("CREATE INDEX idx_stop_area_display_names_public "
                            + "ON stop_area_display_names(public_display_name)");
                    statement.execute("CREATE INDEX idx_stop_area_display_names_normalized "
                            + "ON stop_area_display_names(public_display_name_normalized)");
                }
            }
            connection.commit();
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("DETACH DATABASE source");
                statement.execute("VACUUM");
            }
            return readRowCounts(connection, profile);
        }
    }

    private static SourceContract validateSourceContract(
            Path source,
            List<String> seeds,
            ArtifactProfile profile
    ) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            validateTablesAndColumns(connection, null, profile);
            Map<String, String> metadata = readMetadata(connection);
            requireMetadata(metadata, "schema_version", SqliteContract.SCHEMA_VERSION);
            requireMetadata(metadata, "contract_name", SqliteContract.CONTRACT_NAME);
            requireMetadata(metadata, "contract_version", SqliteContract.APP_RUNTIME_CONTRACT_VERSION);
            requireMetadata(metadata, "preprocessor_version", SqliteContract.PREPROCESSOR_VERSION);
            requireMetadata(metadata, "run_mode", "APP_RUNTIME");
            requireMetadata(metadata, "time_model", SqliteContract.TIME_MODEL);
            requireMetadata(metadata, "stop_id_policy", SqliteContract.STOP_ID_POLICY);
            requireMetadata(metadata, "area_id_policy", SqliteContract.AREA_ID_POLICY);
            requireMetadata(metadata, "search_tokens_policy", SqliteContract.SEARCH_TOKENS_POLICY);
            requireMetadata(metadata, "display_name_transformation_version",
                    SqliteContract.DISPLAY_NAME_TRANSFORMATION_VERSION);
            requireMetadata(metadata, "display_name_transformation_policy",
                    SqliteContract.DISPLAY_NAME_TRANSFORMATION_POLICY);
            requireMetadata(metadata, "service_day_resolution_policy", SqliteContract.SERVICE_DAY_RESOLUTION_POLICY);
            requireMetadata(metadata, "service_day_timezone_policy", SqliteContract.SERVICE_DAY_TIMEZONE_POLICY);
            requireMetadata(metadata, "service_day_time_overflow_policy", SqliteContract.SERVICE_DAY_TIME_OVERFLOW_POLICY);
            String identity = metadata.get("build_identity_sha256");
            if (identity == null || !identity.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Source build_identity_sha256 is missing or invalid");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT 1 FROM stop_areas WHERE area_id = ? LIMIT 1")) {
                for (String seed : seeds) {
                    statement.setString(1, seed);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        if (!resultSet.next()) {
                            throw new IllegalArgumentException("Seed area_id is unknown: " + seed);
                        }
                    }
                }
            }
            return new SourceContract(
                    metadata.get("contract_version"),
                    metadata.get("preprocessor_version"),
                    identity
            );
        }
    }

    private static void validateReducedContract(
            Path database,
            MobileArtifactManifest manifest,
            ArtifactProfile profile
    )
            throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
                try (ResultSet resultSet = statement.executeQuery("PRAGMA integrity_check")) {
                    if (!resultSet.next() || !"ok".equalsIgnoreCase(resultSet.getString(1))) {
                        throw new IllegalArgumentException("Reduced mobile database integrity check failed");
                    }
                }
            }
            validateTablesAndColumns(connection, manifest, profile);
            Map<String, String> metadata = readMetadata(connection);
            requireMetadata(metadata, "contract_version", manifest.sourceContractVersion());
            requireMetadata(metadata, "preprocessor_version", manifest.sourcePreprocessorVersion());
            requireMetadata(metadata, "build_identity_sha256", manifest.sourceBuildIdentitySha256());
            requireMetadata(metadata, "display_name_transformation_version",
                    SqliteContract.DISPLAY_NAME_TRANSFORMATION_VERSION);
            requireMetadata(metadata, "display_name_transformation_policy",
                    SqliteContract.DISPLAY_NAME_TRANSFORMATION_POLICY);
            requireMetadata(metadata, "mobile_artifact_profile", profile.artifactProfile());
            requireMetadata(metadata, "mobile_artifact_id", manifest.artifactId());
            requireMetadata(metadata, "mobile_parent_database_sha256", manifest.sourceDatabaseSha256());
            if (count(connection, "SELECT COUNT(*) FROM trips") < 1
                    || count(connection, "SELECT COUNT(*) FROM stop_times") < 2) {
                throw new IllegalArgumentException("Reduced mobile database contains no usable trip evidence");
            }
            if (count(connection, "SELECT COUNT(*) FROM stop_areas areas "
                    + "WHERE areas.stop_count <> (SELECT COUNT(*) FROM stop_area_members members "
                    + "WHERE members.area_id = areas.area_id)") > 0) {
                throw new IllegalArgumentException("Reduced mobile StopArea counts are inconsistent");
            }
            if (count(connection, "SELECT COUNT(*) FROM service_calendar_summary summary "
                    + "WHERE summary.trip_count <> (SELECT COUNT(*) FROM trips "
                    + "WHERE trips.service_id = summary.service_id)") > 0) {
                throw new IllegalArgumentException("Reduced mobile service trip counts are inconsistent");
            }
            if (profile.searchable()) {
                requireMetadata(metadata, "mobile_search_data_policy",
                        "display_and_tokens_search_only_not_routing");
                requireMetadata(metadata, "mobile_update_contract_version",
                        MobileArtifactUpdateContract.DESCRIPTOR_VERSION);
                if (count(connection, "SELECT COUNT(*) FROM stop_search_tokens") < 1) {
                    throw new IllegalArgumentException("Searchable mobile database contains no search tokens");
                }
                if (count(connection, "SELECT COUNT(*) FROM stop_search_tokens tokens "
                        + "LEFT JOIN stop_areas areas ON areas.area_id = tokens.area_id "
                        + "WHERE areas.area_id IS NULL") > 0) {
                    throw new IllegalArgumentException("Searchable mobile database contains dangling token areas");
                }
                if (count(connection, "SELECT COUNT(*) FROM stop_search_tokens tokens "
                        + "LEFT JOIN stops ON stops.stop_id = tokens.stop_id "
                        + "WHERE tokens.stop_id IS NOT NULL AND stops.stop_id IS NULL") > 0) {
                    throw new IllegalArgumentException("Searchable mobile database contains dangling token stops");
                }
                if (count(connection, "SELECT COUNT(*) FROM stop_areas areas "
                        + "LEFT JOIN stop_area_display_names names ON names.area_id = areas.area_id "
                        + "WHERE names.area_id IS NULL OR TRIM(names.public_display_name) = ''") > 0) {
                    throw new IllegalArgumentException("Searchable mobile StopAreas lack display names");
                }
                if (count(connection, "SELECT COUNT(*) FROM stop_area_display_names names "
                        + "LEFT JOIN canonical_stop_area_names canonical "
                        + "ON canonical.canonical_area_id = names.canonical_area_id "
                        + "WHERE canonical.canonical_area_id IS NULL") > 0) {
                    throw new IllegalArgumentException("Searchable mobile display names lack canonical names");
                }
                for (String index : List.of(
                        "idx_stop_search_tokens_token",
                        "idx_stop_search_tokens_area_id",
                        "idx_stop_search_tokens_token_area",
                        "idx_stop_area_aliases_area_normalized",
                        "idx_canonical_stop_area_names_normalized",
                        "idx_stop_area_display_names_normalized"
                )) {
                    if (count(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type='index' "
                            + "AND name='" + index + "'") != 1) {
                        throw new IllegalArgumentException("Searchable mobile index is missing: " + index);
                    }
                }
            }
        }
    }

    private static void validateTablesAndColumns(
            Connection connection,
            MobileArtifactManifest manifest,
            ArtifactProfile profile
    )
            throws SQLException {
        for (String table : profile.tables()) {
            if (count(connection, "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='"
                    + table + "'") != 1) {
                throw new IllegalArgumentException("Required mobile table is missing: " + table);
            }
            Set<String> columns = new LinkedHashSet<>();
            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
                while (resultSet.next()) {
                    columns.add(resultSet.getString("name"));
                }
            }
            for (String column : SEARCHABLE_REQUIRED_COLUMNS.get(table)) {
                if (!columns.contains(column)) {
                    throw new IllegalArgumentException("Required mobile column is missing: "
                            + table + "." + column);
                }
            }
        }
        if (manifest != null) {
            for (Map.Entry<String, Long> expected : manifest.rowCounts().entrySet()) {
                long actual = count(connection, "SELECT COUNT(*) FROM " + expected.getKey());
                if (actual != expected.getValue()) {
                    throw new IllegalArgumentException("Manifest row count mismatch for " + expected.getKey());
                }
            }
        }
    }

    private static void validateManifest(
            MobileArtifactManifest manifest,
            PublicKey publicKey,
            long maximumBytes,
            ArtifactProfile profile
    ) throws GeneralSecurityException {
        requireValue("manifestVersion", profile.manifestVersion(), manifest.manifestVersion());
        requireValue("packagerVersion", profile.packagerVersion(), manifest.packagerVersion());
        requireValue("artifactProfile", profile.artifactProfile(), manifest.artifactProfile());
        requireValue("databaseFile", DATABASE_FILE, manifest.databaseFile());
        requireValue("sourceContractVersion", SqliteContract.APP_RUNTIME_CONTRACT_VERSION,
                manifest.sourceContractVersion());
        requireValue("sourcePreprocessorVersion", SqliteContract.PREPROCESSOR_VERSION,
                manifest.sourcePreprocessorVersion());
        requireValue("signatureAlgorithm", MobileArtifactCrypto.SIGNATURE_ALGORITHM,
                manifest.signatureAlgorithm());
        requireValue("keyId", MobileArtifactCrypto.keyId(publicKey), manifest.keyId());
        validateArtifactId(manifest.artifactId());
        if (manifest.databaseBytes() < 1 || manifest.databaseBytes() > maximumBytes) {
            throw new IllegalArgumentException("Manifest databaseBytes is outside the allowed range");
        }
        if (!isSha256(manifest.databaseSha256())
                || !isSha256(manifest.sourceDatabaseSha256())
                || !isSha256(manifest.sourceBuildIdentitySha256())) {
            throw new IllegalArgumentException("Manifest contains an invalid SHA-256 value");
        }
        if (manifest.seedAreaIds() == null || manifest.seedAreaIds().isEmpty()) {
            throw new IllegalArgumentException("Manifest must contain seed area IDs");
        }
        if (manifest.rowCounts() == null
                || !manifest.rowCounts().keySet().equals(Set.copyOf(profile.tables()))) {
            throw new IllegalArgumentException("Manifest row counts do not match the mobile table set");
        }
    }

    private static Map<String, Long> readRowCounts(Connection connection, ArtifactProfile profile)
            throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : profile.tables()) {
            counts.put(table, count(connection, "SELECT COUNT(*) FROM " + table));
        }
        return Map.copyOf(counts);
    }

    private static String sourceCreateSql(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM source.sqlite_master WHERE type='table' AND name=?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next() || resultSet.getString(1) == null) {
                    throw new IllegalArgumentException("Cannot copy source schema for table: " + table);
                }
                return resultSet.getString(1);
            }
        }
    }

    private static Map<String, String> readMetadata(Connection connection) throws SQLException {
        Map<String, String> metadata = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT key, value FROM ixit_metadata")) {
            while (resultSet.next()) {
                metadata.put(resultSet.getString(1), resultSet.getString(2));
            }
        }
        return Map.copyOf(metadata);
    }

    private static void upsertMetadata(Connection connection, String key, String value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO ixit_metadata(key, value) VALUES (?, ?)")) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static long count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static void requireMetadata(Map<String, String> metadata, String key, String expected) {
        requireValue("ixit_metadata." + key, expected, metadata.get(key));
    }

    private static void requireValue(String label, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(label + " must be " + expected + " but was "
                    + (actual == null || actual.isBlank() ? "<missing>" : actual));
        }
    }

    private static Path requireSource(Path requested) {
        if (requested == null) {
            throw new IllegalArgumentException("Source database is required");
        }
        Path source = requested.toAbsolutePath().normalize();
        if (!Files.isRegularFile(source)) {
            throw new IllegalArgumentException("Source database does not exist: " + source);
        }
        if (Files.exists(Path.of(source + "-wal")) || Files.exists(Path.of(source + "-shm"))) {
            throw new IllegalArgumentException("Source database must be finalized without WAL/SHM sidecars");
        }
        return source;
    }

    private static Path requireRegularPackageFile(Path directory, String name) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Mobile package directory is missing or symbolic: " + root);
        }
        Path child = root.resolve(name).normalize();
        if (!child.getParent().equals(root)
                || !Files.isRegularFile(child)
                || Files.isSymbolicLink(child)
                || !child.toRealPath().getParent().equals(root.toRealPath())) {
            throw new IllegalArgumentException("Mobile package file is missing or outside package: " + name);
        }
        return child;
    }

    private static List<String> normalizeSeeds(List<String> values) {
        if (values == null) {
            throw new IllegalArgumentException("At least one seed area_id is required");
        }
        Set<String> seeds = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Seed area_id must not be blank");
            }
            seeds.add(value.trim());
        }
        if (seeds.isEmpty() || seeds.size() > 100) {
            throw new IllegalArgumentException("Seed area_id count must be between 1 and 100");
        }
        return List.copyOf(seeds);
    }

    private static void validateArtifactId(String artifactId) {
        if (artifactId == null || !artifactId.matches("[a-z0-9][a-z0-9._-]{2,79}")) {
            throw new IllegalArgumentException("artifactId must match [a-z0-9][a-z0-9._-]{2,79}");
        }
    }

    private static void verifyKeyPair(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        byte[] probe = "IXIT_MOBILE_ARTIFACT_KEY_PAIR_V0_1".getBytes(StandardCharsets.US_ASCII);
        if (!MobileArtifactCrypto.verify(probe, MobileArtifactCrypto.sign(probe, privateKey), publicKey)) {
            throw new IllegalArgumentException("Signing private key does not match public key");
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sqliteLiteral(Path path) {
        return path.toAbsolutePath().normalize().toString().replace("'", "''");
    }

    private static void deleteStagingTree(Path staging, Path expectedParent) throws IOException {
        Path normalized = staging.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(expectedParent.toAbsolutePath().normalize())
                || !normalized.getFileName().toString().contains(".staging-")) {
            throw new IOException("Refusing to delete unexpected staging path: " + normalized);
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record SourceContract(
            String contractVersion,
            String preprocessorVersion,
            String buildIdentitySha256
    ) {
    }

    private record ArtifactProfile(
            String manifestVersion,
            String packagerVersion,
            String artifactProfile,
            String manifestFile,
            String signatureFile,
            List<String> tables,
            boolean searchable
    ) {
    }
}
