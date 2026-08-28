package de.ixit.gtfs;

import de.ixit.gtfs.model.StopAreaDisplayName;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisplayNameAuditor {
    public static final String AUDIT_VERSION = SqliteContract.PREPROCESSOR_VERSION;

    private static final int MAX_SAMPLES = 25;
    private static final Pattern LEADING_QUALIFIER = Pattern.compile("^\\(([^)]+)\\)\\s+(.+)$");
    private static final Pattern UNKNOWN_CODE_PREFIX = Pattern.compile("^([\\p{Lu}]{2,5})[-.]+(.+)$");
    private static final Set<String> MODE_CODES = Set.of("S", "U", "SU", "BUS", "TRAM");

    private DisplayNameAuditor() {
    }

    public static DisplayNameAuditReport audit(Connection connection) throws SQLException {
        if (!hasTable(connection, "stop_areas")
                || !hasTable(connection, "stop_area_cities")
                || !hasTable(connection, "stop_area_display_names")) {
            return new DisplayNameAuditReport(
                    AUDIT_VERSION,
                    false,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    Map.of(),
                    List.of("MISSING_REQUIRED_TABLE")
            );
        }

        CityPrefixAliasResolver.Builder resolverBuilder = CityPrefixAliasResolver.builder();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT area.area_name, city.city_name
                     FROM stop_areas area
                     JOIN stop_area_cities city ON city.area_id = area.area_id
                     """)) {
            while (resultSet.next()) {
                resolverBuilder.observe(resultSet.getString(1), resultSet.getString(2));
            }
        }
        CityPrefixAliasResolver resolver = resolverBuilder.build();

        Accumulator accumulator = accumulator(resolver);

        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT area_id, public_stop_name, public_city_name, public_display_name, explanation
                     FROM stop_area_display_names
                     """)) {
            while (resultSet.next()) {
                accumulator.accept(
                        resultSet.getString("area_id"),
                        resultSet.getString("public_stop_name"),
                        resultSet.getString("public_city_name"),
                        resultSet.getString("public_display_name"),
                        DisplayNameTransformationRules.extract(resultSet.getString("explanation"))
                );
            }
        }
        return accumulator.report();
    }

    public static Accumulator accumulator(CityPrefixAliasResolver resolver) {
        return new Accumulator(resolver);
    }

    private static boolean startsWithNormalizedCity(String stopName, String cityName) {
        String stop = StopNameNormalizer.normalize(stopName);
        String city = StopNameNormalizer.normalize(cityName);
        return !city.isBlank() && stop.startsWith(city + " ");
    }

    private static boolean sameNormalizedCity(String stopName, String cityName) {
        String city = StopNameNormalizer.normalize(cityName);
        return !city.isBlank() && StopNameNormalizer.normalize(stopName).equals(city);
    }

    private static boolean hasMatchingCityQualifier(String stopName, String cityName) {
        Matcher matcher = LEADING_QUALIFIER.matcher(stopName);
        if (!matcher.matches()) {
            return false;
        }
        String qualifier = StopNameNormalizer.normalize(matcher.group(1));
        String city = StopNameNormalizer.normalize(cityName);
        return qualifier.length() >= 3 && (" " + city + " ").contains(" " + qualifier + " ");
    }

    private static boolean hasTable(Connection connection, String table) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void addSample(List<String> samples, String type, String areaId, String displayName) {
        String category = type.contains(":") ? type.substring(0, type.indexOf(':')) : type;
        long categorySamples = samples.stream().filter(sample -> sample.startsWith(category + ":")).count();
        if (samples.size() < MAX_SAMPLES && categorySamples < 5) {
            samples.add(type + ":" + areaId + "=" + displayName);
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public static final class Accumulator {
        private final CityPrefixAliasResolver resolver;
        private final List<String> samples = new ArrayList<>();
        private long scanned;
        private long formatMismatches;
        private long municipalityOnlyNames;
        private long duplicateCityNames;
        private long matchingCityCodes;
        private long matchingCityQualifiers;
        private long suspiciousUnknownPrefixes;
        private long transformedNames;
        private long invalidTransformationRuleRows;
        private final Map<String, Long> transformationRuleCounts = new LinkedHashMap<>();

        private Accumulator(CityPrefixAliasResolver resolver) {
            this.resolver = resolver;
        }

        public void accept(StopAreaDisplayName name) {
            accept(
                    name.areaId(),
                    name.publicStopName(),
                    name.publicCityName(),
                    name.publicDisplayName(),
                    DisplayNameTransformationRules.extract(name.explanation())
            );
        }

        public void accept(String areaIdValue, String stopNameValue, String cityNameValue, String displayNameValue) {
            accept(areaIdValue, stopNameValue, cityNameValue, displayNameValue, DisplayNameTransformationRules.NONE);
        }

        public void accept(
                String areaIdValue,
                String stopNameValue,
                String cityNameValue,
                String displayNameValue,
                String transformationRules
        ) {
            String areaId = clean(areaIdValue);
            String stopName = clean(stopNameValue);
            String cityName = clean(cityNameValue);
            String displayName = clean(displayNameValue);
            try {
                List<String> decodedRules = DisplayNameTransformationRules.decode(transformationRules);
                if (!decodedRules.isEmpty()) {
                    transformedNames++;
                }
                for (String rule : decodedRules) {
                    transformationRuleCounts.merge(rule, 1L, Long::sum);
                }
            } catch (IllegalArgumentException exception) {
                invalidTransformationRuleRows++;
                addSample(samples, "TRANSFORMATION_RULES", areaId, transformationRules);
            }
            if (stopName.isBlank() || cityName.isBlank()) {
                return;
            }
            scanned++;

            String expectedDisplay = stopName + ", " + cityName;
            if (!displayName.equals(expectedDisplay)) {
                formatMismatches++;
                addSample(samples, "FORMAT", areaId, displayName);
            }

            if (sameNormalizedCity(stopName, cityName)) {
                municipalityOnlyNames++;
                addSample(samples, "MUNICIPALITY_ONLY", areaId, displayName);
            } else if (startsWithNormalizedCity(stopName, cityName)) {
                duplicateCityNames++;
                addSample(samples, "CITY_NAME", areaId, displayName);
            }

            CityPrefixAliasResolver.PrefixMatch match = resolver.stripLeadingPrefix(stopName, cityName);
            if (match.stripped()) {
                matchingCityCodes++;
                addSample(samples, "CITY_CODE:" + match.prefix(), areaId, displayName);
            }

            if (hasMatchingCityQualifier(stopName, cityName)) {
                matchingCityQualifiers++;
                addSample(samples, "CITY_QUALIFIER", areaId, displayName);
            }

            Matcher unknownPrefix = UNKNOWN_CODE_PREFIX.matcher(stopName);
            if (unknownPrefix.matches()) {
                String code = unknownPrefix.group(1).toUpperCase(Locale.ROOT);
                if (!MODE_CODES.contains(code) && !match.stripped()) {
                    suspiciousUnknownPrefixes++;
                    addSample(samples, "UNKNOWN_PREFIX:" + code, areaId, displayName);
                }
            }
        }

        public DisplayNameAuditReport report() {
            Map<String, Long> orderedRuleCounts = new LinkedHashMap<>();
            for (String rule : DisplayNameTransformationRules.orderedRules()) {
                Long count = transformationRuleCounts.get(rule);
                if (count != null) {
                    orderedRuleCounts.put(rule, count);
                }
            }
            return new DisplayNameAuditReport(
                    AUDIT_VERSION,
                    true,
                    scanned,
                    formatMismatches,
                    municipalityOnlyNames,
                    duplicateCityNames,
                    matchingCityCodes,
                    matchingCityQualifiers,
                    suspiciousUnknownPrefixes,
                    transformedNames,
                    invalidTransformationRuleRows,
                    Collections.unmodifiableMap(orderedRuleCounts),
                    List.copyOf(samples)
            );
        }
    }
}
