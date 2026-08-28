package de.ixit.gtfs;

import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only spotcheck of existing stop-area coordinates against a municipality boundary dataset. */
public final class MunicipalitySpotcheckCli {
    private MunicipalitySpotcheckCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        long loadStarted = System.nanoTime();
        MunicipalityGeoJsonIndex index = MunicipalityGeoJsonIndex.load(
                arguments.geoJson(),
                arguments.dataVersion()
        );
        long loadMillis = (System.nanoTime() - loadStarted) / 1_000_000L;

        System.out.println("VG250 municipality spotcheck");
        System.out.println("dataVersion=" + index.dataVersion());
        System.out.println("municipalityFeatureCount=" + index.municipalityCount());
        System.out.println("indexLoadMillis=" + loadMillis);
        System.out.println("areaId\trawName\toldDisplayName\toldCity\tresolvedCity\tmunicipalityId\tmunicipalityType");

        String jdbcUrl = "jdbc:sqlite:" + arguments.database().toAbsolutePath();
        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.setReadOnly(true);
        try (Connection connection = sqliteConfig.createConnection(jdbcUrl)) {
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA query_only = ON");
            }
            for (String areaId : arguments.areaIds()) {
                printArea(connection, index, areaId);
            }
        }
    }

    private static void printArea(
            Connection connection,
            MunicipalityGeoJsonIndex index,
            String areaId
    ) throws Exception {
        String sql = """
                SELECT area.area_id,
                       area.area_name,
                       area.area_lat,
                       area.area_lon,
                       COALESCE(display.public_display_name, ''),
                       COALESCE(display.public_city_name, '')
                FROM stop_areas area
                LEFT JOIN stop_area_display_names display ON display.area_id = area.area_id
                WHERE area.area_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, areaId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    System.out.println(tsv(areaId, "<not found>", "", "", "", "", ""));
                    return;
                }
                Double latitude = nullableDouble(result, 3);
                Double longitude = nullableDouble(result, 4);
                MunicipalityGeoJsonIndex.ResolvedMunicipality municipality = latitude == null || longitude == null
                        ? null
                        : index.resolve(latitude, longitude).orElse(null);
                System.out.println(tsv(
                        result.getString(1),
                        result.getString(2),
                        result.getString(5),
                        result.getString(6),
                        municipality == null ? "" : municipality.cityName(),
                        municipality == null ? "" : municipality.municipalityId(),
                        municipality == null ? "" : municipality.municipalityType()
                ));
            }
        }
    }

    private static Double nullableDouble(ResultSet result, int column) throws Exception {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static String tsv(String... values) {
        ArrayList<String> escaped = new ArrayList<>(values.length);
        for (String value : values) {
            escaped.add(value == null ? "" : value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' '));
        }
        return String.join("\t", escaped);
    }

    private record Arguments(Path database, Path geoJson, String dataVersion, List<String> areaIds) {
        private static Arguments parse(String[] args) {
            Map<String, List<String>> values = new LinkedHashMap<>();
            for (int index = 0; index < args.length; index++) {
                String key = args[index];
                if (!key.startsWith("--") || index + 1 >= args.length) {
                    throw usage("Expected --key value, got: " + key);
                }
                values.computeIfAbsent(key, ignored -> new ArrayList<>()).add(args[++index]);
            }

            Path database = Path.of(requiredSingle(values, "--database"));
            Path geoJson = Path.of(requiredSingle(values, "--geojson"));
            String dataVersion = requiredSingle(values, "--version");
            List<String> areaIds = values.getOrDefault("--area-id", List.of()).stream()
                    .flatMap(value -> List.of(value.split(",")).stream())
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
            if (areaIds.isEmpty()) {
                throw usage("At least one --area-id is required.");
            }
            return new Arguments(database, geoJson, dataVersion, areaIds);
        }

        private static String requiredSingle(Map<String, List<String>> values, String key) {
            List<String> candidates = values.get(key);
            if (candidates == null || candidates.size() != 1 || candidates.getFirst().isBlank()) {
                throw usage("Exactly one " + key + " is required.");
            }
            return candidates.getFirst();
        }

        private static IllegalArgumentException usage(String message) {
            return new IllegalArgumentException(message + System.lineSeparator()
                    + "Usage: MunicipalitySpotcheckCli --database runtime.sqlite "
                    + "--geojson municipalities.geojson --version YYYY-MM-DD "
                    + "--area-id ID [--area-id ID]");
        }
    }
}
