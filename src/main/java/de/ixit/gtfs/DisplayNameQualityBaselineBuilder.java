package de.ixit.gtfs;

import de.ixit.gtfs.model.DisplayNameQualityFinding;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisplayNameQualityBaselineBuilder {
    public static final String BASELINE_VERSION = "0.6.5";
    public static final String ACTION_PRESERVE = "PRESERVE";

    private static final Pattern LEADING_ACRONYM = Pattern.compile("^([\\p{Lu}]{2,5})[-.]+(.+)$");
    private static final Set<String> MODE_CODES = Set.of("S", "U", "SU", "BUS", "TRAM");
    private static final Set<String> INSTITUTION_CODES = Set.of(
            "HEIG", "UNIL", "KIT", "HAWK", "TU", "DFB", "LWL", "LVR"
    );
    private static final Set<String> TRANSIT_OR_INFRASTRUCTURE_CODES = Set.of(
            "AST", "BAB", "BSAG", "GVZ", "KVG", "OEG", "RVE", "RVK", "SBB", "SSB", "ZUP"
    );
    private static final Set<String> NAMED_ENTITY_CODES = Set.of(
            "AOK", "ASB", "AWO", "BMW", "BRK", "DRK", "EDEKA", "ERGO", "JVA", "REWE", "SAP", "VW"
    );
    private static final Set<String> ROMAN_SECTION_CODES = Set.of("II", "III", "IV", "VI", "VII", "VIII", "IX");
    private static final int MAX_SAMPLES = 25;

    private DisplayNameQualityBaselineBuilder() {
    }

    public static BuildResult buildFromDatabase(Path databasePath) throws SQLException {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath())) {
            return build(connection);
        }
    }

    static BuildResult build(Connection connection) throws SQLException {
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

        List<DisplayNameQualityFinding> findings = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT display.area_id,
                            display.public_stop_name,
                            display.public_city_name,
                            display.public_display_name,
                            area.area_name
                     FROM stop_area_display_names display
                     LEFT JOIN stop_areas area ON area.area_id = display.area_id
                     ORDER BY display.area_id
                     """)) {
            while (resultSet.next()) {
                String areaId = clean(resultSet.getString("area_id"));
                String stopName = clean(resultSet.getString("public_stop_name"));
                String cityName = clean(resultSet.getString("public_city_name"));
                String displayName = clean(resultSet.getString("public_display_name"));
                String originalName = clean(resultSet.getString("area_name"));
                if (stopName.isBlank() || cityName.isBlank()) {
                    continue;
                }

                if (sameNormalized(stopName, cityName)) {
                    boolean sourceQualifier = originalName.contains("(") && originalName.contains(")");
                    findings.add(new DisplayNameQualityFinding(
                            areaId,
                            "MUNICIPALITY_ONLY",
                            sourceQualifier ? "LOCALITY_STOP_WITH_SOURCE_QUALIFIER" : "LOCALITY_STOP_NAME",
                            "",
                            stopName,
                            cityName,
                            displayName,
                            ACTION_PRESERVE,
                            sourceQualifier
                                    ? "The feed names the stop after the locality and carries a source qualifier; no public suffix is invented."
                                    : "The feed names the stop after the locality; no station suffix is invented."
                    ));
                }

                Matcher matcher = LEADING_ACRONYM.matcher(stopName);
                if (!matcher.matches()) {
                    continue;
                }
                String code = matcher.group(1).toUpperCase(Locale.ROOT);
                if (MODE_CODES.contains(code) || resolver.stripLeadingPrefix(stopName, cityName).stripped()) {
                    continue;
                }
                PrefixClassification classification = classifyPrefix(code, cityName);
                findings.add(new DisplayNameQualityFinding(
                        areaId,
                        "UPPERCASE_PREFIX",
                        classification.classification(),
                        code,
                        stopName,
                        cityName,
                        displayName,
                        ACTION_PRESERVE,
                        classification.rationale()
                ));
            }
        }
        return new BuildResult(List.copyOf(findings), report(findings, 0, 0));
    }

    public static PrefixClassification classifyPrefix(String codeValue, String cityName) {
        String code = clean(codeValue).toUpperCase(Locale.ROOT);
        if (INSTITUTION_CODES.contains(code)) {
            return new PrefixClassification(
                    "INSTITUTION_NAME",
                    "The prefix identifies an educational, civic or public institution and is part of the stop name."
            );
        }
        if (TRANSIT_OR_INFRASTRUCTURE_CODES.contains(code)) {
            return new PrefixClassification(
                    "TRANSIT_OR_INFRASTRUCTURE_TERM",
                    "The prefix is a transport or infrastructure term and is not a municipality marker."
            );
        }
        if (NAMED_ENTITY_CODES.contains(code)) {
            return new PrefixClassification(
                    "NAMED_ENTITY",
                    "The prefix identifies an organization, employer or commercial facility and is retained."
            );
        }
        if (ROMAN_SECTION_CODES.contains(code)) {
            return new PrefixClassification(
                    "SECTION_OR_ZONE_LABEL",
                    "The prefix is a section or zone label, not a city abbreviation."
            );
        }
        if (looksLikeCityCode(code, cityName)) {
            return new PrefixClassification(
                    "LOCALITY_CODE_CANDIDATE",
                    "The prefix resembles the resolved locality but lacks enough evidence for automatic removal."
            );
        }
        return new PrefixClassification(
                "PRESERVED_ACRONYM",
                "The uppercase prefix is ambiguous and is retained until feed-specific evidence proves it is a city marker."
        );
    }

    static DisplayNameQualityBaselineReport report(
            List<DisplayNameQualityFinding> findings,
            long coverageGaps,
            long destructiveActions
    ) {
        Map<String, Long> classificationCounts = new LinkedHashMap<>();
        long prefixes = 0;
        long municipalityOnly = 0;
        List<String> samples = new ArrayList<>();
        for (DisplayNameQualityFinding finding : findings) {
            classificationCounts.merge(finding.classification(), 1L, Long::sum);
            if ("UPPERCASE_PREFIX".equals(finding.findingType())) {
                prefixes++;
            } else if ("MUNICIPALITY_ONLY".equals(finding.findingType())) {
                municipalityOnly++;
            }
            if (samples.size() < MAX_SAMPLES) {
                samples.add(finding.findingType()
                        + ":"
                        + finding.classification()
                        + ":"
                        + finding.areaId()
                        + "="
                        + finding.publicDisplayName());
            }
        }
        return new DisplayNameQualityBaselineReport(
                BASELINE_VERSION,
                true,
                findings.size(),
                prefixes,
                municipalityOnly,
                coverageGaps,
                destructiveActions,
                Map.copyOf(classificationCounts),
                List.copyOf(samples)
        );
    }

    private static boolean looksLikeCityCode(String code, String cityName) {
        String city = StopNameNormalizer.normalize(cityName)
                .replaceAll("[^a-z0-9]", "")
                .toUpperCase(Locale.ROOT);
        if (code.length() < 2 || city.isBlank()) {
            return false;
        }
        int cityIndex = 0;
        for (int codeIndex = 0; codeIndex < code.length(); codeIndex++) {
            cityIndex = city.indexOf(code.charAt(codeIndex), cityIndex);
            if (cityIndex < 0) {
                return false;
            }
            cityIndex++;
        }
        return true;
    }

    private static boolean sameNormalized(String first, String second) {
        String normalized = StopNameNormalizer.normalize(first);
        return !normalized.isBlank() && normalized.equals(StopNameNormalizer.normalize(second));
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record BuildResult(
            List<DisplayNameQualityFinding> findings,
            DisplayNameQualityBaselineReport report
    ) {
    }

    public record PrefixClassification(String classification, String rationale) {
    }
}
