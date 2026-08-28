package de.ixit.gtfs;

import de.ixit.gtfs.model.CanonicalStopArea;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaCity;
import de.ixit.gtfs.model.StopAreaDisplayName;
import org.sqlite.SQLiteConfig;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Refreshes materialized public stop-area names without rebuilding the large GTFS core tables. */
public final class StopAreaDisplayNameRefreshCli {
    private static final int MAX_PRINTED_CHANGES = 30;

    private StopAreaDisplayNameRefreshCli() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(!arguments.apply());
        Path database = arguments.database().toAbsolutePath().normalize();
        String jdbcUrl = arguments.apply()
                ? "jdbc:sqlite:" + database
                : "jdbc:sqlite:" + database.toUri() + "?mode=ro&immutable=1";
        try (Connection connection = config.createConnection(jdbcUrl)) {
            if (!arguments.apply()) {
                try (var statement = connection.createStatement()) {
                    statement.execute("PRAGMA query_only = ON");
                }
            }
            RefreshResult result = refresh(connection, arguments.apply());
            System.out.println("mode=" + (arguments.apply() ? "APPLY" : "DRY_RUN"));
            System.out.println("scanned=" + result.scanned());
            System.out.println("changed=" + result.changed());
            System.out.println("printed=" + result.printed());
            System.out.println("learned_city_prefix_aliases=" + result.learnedCityPrefixAliases());
            DisplayNameAuditReport projectedAudit = result.projectedAudit();
            System.out.println("projected_audit_pass=" + projectedAudit.pass());
            System.out.println("projected_audit_scanned=" + projectedAudit.scannedNames());
            System.out.println("projected_format_mismatches=" + projectedAudit.formatMismatches());
            System.out.println("projected_municipality_only_names=" + projectedAudit.municipalityOnlyNames());
            System.out.println("projected_duplicate_city_name_prefixes=" + projectedAudit.duplicateCityNamePrefixes());
            System.out.println("projected_matching_city_code_prefixes=" + projectedAudit.matchingCityCodePrefixes());
            System.out.println("projected_matching_city_qualifiers=" + projectedAudit.matchingCityQualifiers());
            System.out.println("projected_suspicious_unknown_prefixes=" + projectedAudit.suspiciousUnknownPrefixes());
            for (String sample : projectedAudit.samples()) {
                System.out.println("projected_audit_sample=" + sample);
            }
        }
    }

    private static RefreshResult refresh(Connection connection, boolean apply) throws Exception {
        String selectSql = """
                SELECT area.area_id,
                       area.area_name,
                       area.area_lat,
                       area.area_lon,
                       area.stop_count,
                       city.municipality_id,
                       city.city_name,
                       city.municipality_type,
                       city.source,
                       city.quality,
                       city.data_version,
                       city.explanation,
                       canonical.canonical_area_id,
                       canonical.canonical_display_name,
                       canonical.original_name,
                       canonical.city_name,
                       canonical.station_name,
                       canonical.name_order,
                       canonical.primary_stop_area_id,
                       canonical.profile_class,
                       canonical.has_rail_service,
                       canonical.line_labels,
                       canonical.member_count,
                       canonical.display_quality,
                       canonical.source,
                       canonical.explanation,
                       display.public_display_name,
                       display.public_display_name_normalized,
                       display.public_stop_name,
                       display.public_city_name,
                       display.display_quality,
                       display.source,
                       display.explanation
                FROM stop_areas area
                JOIN canonical_stop_area_members member ON member.area_id = area.area_id
                JOIN canonical_stop_areas canonical ON canonical.canonical_area_id = member.canonical_area_id
                JOIN stop_area_display_names display ON display.area_id = area.area_id
                LEFT JOIN stop_area_cities city ON city.area_id = area.area_id
                ORDER BY area.area_id
                """;
        String updateSql = """
                UPDATE stop_area_display_names
                SET canonical_area_id = ?,
                    public_display_name = ?,
                    public_display_name_normalized = ?,
                    public_stop_name = ?,
                    public_city_name = ?,
                    display_quality = ?,
                    source = ?,
                    explanation = ?
                WHERE area_id = ?
                """;

        CityPrefixAliasResolver prefixResolver = cityPrefixAliasResolver(connection);
        DisplayNameAuditor.Accumulator projectedAudit = DisplayNameAuditor.accumulator(prefixResolver);
        int scanned = 0;
        int changed = 0;
        int printed = 0;
        if (apply) {
            connection.setAutoCommit(false);
        }
        try (PreparedStatement select = connection.prepareStatement(selectSql);
             PreparedStatement update = apply ? connection.prepareStatement(updateSql) : null;
             ResultSet result = select.executeQuery()) {
            while (result.next()) {
                scanned++;
                StopArea area = new StopArea(
                        result.getString(1),
                        result.getString(2),
                        nullableDouble(result, 3),
                        nullableDouble(result, 4),
                        result.getInt(5)
                );
                StopAreaCity city = result.getString(7) == null ? null : new StopAreaCity(
                        area.areaId(),
                        blank(result.getString(6)),
                        blank(result.getString(7)),
                        blank(result.getString(8)),
                        blank(result.getString(9)),
                        blank(result.getString(10)),
                        blank(result.getString(11)),
                        blank(result.getString(12))
                );
                CanonicalStopArea canonical = new CanonicalStopArea(
                        result.getString(13),
                        result.getString(14),
                        result.getString(15),
                        result.getString(16),
                        result.getString(17),
                        result.getString(18),
                        result.getString(19),
                        result.getString(20),
                        result.getInt(21) != 0,
                        result.getString(22),
                        result.getInt(23),
                        result.getString(24),
                        result.getString(25),
                        result.getString(26)
                );
                StopAreaDisplayName existing = new StopAreaDisplayName(
                        area.areaId(),
                        canonical.canonicalAreaId(),
                        result.getString(27),
                        result.getString(28),
                        result.getString(29),
                        result.getString(30),
                        result.getString(31),
                        result.getString(32),
                        result.getString(33)
                );
                StopAreaDisplayName refreshed = area.areaId().equals(canonical.primaryStopAreaId())
                        ? StopAreaPublicDisplayNameFormatter.forMember(canonical, area.areaId(), city, prefixResolver)
                        : StopAreaPublicDisplayNameFormatter.forFamilyMember(
                                area,
                                canonical.canonicalAreaId(),
                                city,
                                prefixResolver
                        );
                projectedAudit.accept(refreshed);
                if (same(existing, refreshed)) {
                    continue;
                }
                changed++;
                if (printed < MAX_PRINTED_CHANGES) {
                    System.out.println(area.areaId() + "\t" + existing.publicDisplayName()
                            + "\t=>\t" + refreshed.publicDisplayName());
                    printed++;
                }
                if (apply) {
                    bind(update, refreshed);
                    update.addBatch();
                    if (changed % 1_000 == 0) {
                        update.executeBatch();
                    }
                }
            }
            if (apply) {
                update.executeBatch();
                writeMetadata(connection, "display_name_refresh_at", Instant.now().toString());
                writeMetadata(connection, "display_name_refresh_count", Integer.toString(changed));
                connection.commit();
            }
        } catch (Exception exception) {
            if (apply) {
                connection.rollback();
            }
            throw exception;
        } finally {
            if (apply) {
                connection.setAutoCommit(true);
            }
        }
        return new RefreshResult(
                scanned,
                changed,
                printed,
                prefixResolver.learnedAliasCount(),
                projectedAudit.report()
        );
    }

    private static CityPrefixAliasResolver cityPrefixAliasResolver(Connection connection) throws Exception {
        CityPrefixAliasResolver.Builder builder = CityPrefixAliasResolver.builder();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT area.area_name, city.city_name
                FROM stop_areas area
                LEFT JOIN stop_area_cities city ON city.area_id = area.area_id
                """);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                builder.observe(result.getString(1), result.getString(2));
            }
        }
        return builder.build();
    }

    private static boolean same(StopAreaDisplayName left, StopAreaDisplayName right) {
        return Objects.equals(left.canonicalAreaId(), right.canonicalAreaId())
                && Objects.equals(left.publicDisplayName(), right.publicDisplayName())
                && Objects.equals(left.publicDisplayNameNormalized(), right.publicDisplayNameNormalized())
                && Objects.equals(left.publicStopName(), right.publicStopName())
                && Objects.equals(left.publicCityName(), right.publicCityName())
                && Objects.equals(left.displayQuality(), right.displayQuality())
                && Objects.equals(left.source(), right.source())
                && Objects.equals(left.explanation(), right.explanation());
    }

    private static void bind(PreparedStatement update, StopAreaDisplayName name) throws Exception {
        update.setString(1, name.canonicalAreaId());
        update.setString(2, name.publicDisplayName());
        update.setString(3, name.publicDisplayNameNormalized());
        update.setString(4, name.publicStopName());
        update.setString(5, name.publicCityName());
        update.setString(6, name.displayQuality());
        update.setString(7, name.source());
        update.setString(8, name.explanation());
        update.setString(9, name.areaId());
    }

    private static void writeMetadata(Connection connection, String key, String value) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO ixit_metadata(key, value) VALUES (?, ?)
                ON CONFLICT(key) DO UPDATE SET value = excluded.value
                """)) {
            statement.setString(1, key);
            statement.setString(2, value);
            statement.executeUpdate();
        }
    }

    private static Double nullableDouble(ResultSet result, int column) throws Exception {
        double value = result.getDouble(column);
        return result.wasNull() ? null : value;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    private record RefreshResult(
            int scanned,
            int changed,
            int printed,
            int learnedCityPrefixAliases,
            DisplayNameAuditReport projectedAudit
    ) {
    }

    private record Arguments(Path database, boolean apply) {
        private static Arguments parse(String[] args) {
            Path database = null;
            boolean apply = false;
            List<String> tokens = new ArrayList<>(List.of(args));
            for (int index = 0; index < tokens.size(); index++) {
                String token = tokens.get(index);
                if ("--database".equals(token) && index + 1 < tokens.size()) {
                    database = Path.of(tokens.get(++index));
                } else if ("--apply".equals(token)) {
                    apply = true;
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete option: " + token);
                }
            }
            if (database == null) {
                throw new IllegalArgumentException("Usage: StopAreaDisplayNameRefreshCli --database runtime.sqlite [--apply]");
            }
            return new Arguments(database, apply);
        }
    }
}
