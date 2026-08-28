package de.ixit.gtfs;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DisplayNameTransformationRules {
    public static final String NONE = "NONE";
    public static final String CITY_FROM_RESOLVED_CONTEXT = "CITY_FROM_RESOLVED_CONTEXT";
    public static final String CITY_CODE_EXPANDED = "CITY_CODE_EXPANDED";
    public static final String CITY_PREFIX_REMOVED = "CITY_PREFIX_REMOVED";
    public static final String CITY_QUALIFIER_REMOVED = "CITY_QUALIFIER_REMOVED";
    public static final String MODE_PREFIX_REMOVED = "MODE_PREFIX_REMOVED";
    public static final String TECHNICAL_QUALIFIER_REMOVED = "TECHNICAL_QUALIFIER_REMOVED";
    public static final String STATION_ABBREVIATION_EXPANDED = "STATION_ABBREVIATION_EXPANDED";
    public static final String GENERIC_STATION_SUFFIX_REMOVED = "GENERIC_STATION_SUFFIX_REMOVED";
    public static final String STREET_SUFFIX_NORMALIZED = "STREET_SUFFIX_NORMALIZED";
    public static final String LOCALITY_COMPOUND_PRESERVED = "LOCALITY_COMPOUND_PRESERVED";
    public static final String STOP_CITY_COMPOSED = "STOP_CITY_COMPOSED";

    private static final List<String> ORDERED_RULES = List.of(
            CITY_FROM_RESOLVED_CONTEXT,
            CITY_CODE_EXPANDED,
            CITY_PREFIX_REMOVED,
            CITY_QUALIFIER_REMOVED,
            MODE_PREFIX_REMOVED,
            TECHNICAL_QUALIFIER_REMOVED,
            STATION_ABBREVIATION_EXPANDED,
            GENERIC_STATION_SUFFIX_REMOVED,
            STREET_SUFFIX_NORMALIZED,
            LOCALITY_COMPOUND_PRESERVED,
            STOP_CITY_COMPOSED
    );
    private static final Set<String> KNOWN_RULES = Set.copyOf(ORDERED_RULES);
    private static final Pattern EXPLANATION_RULES = Pattern.compile("(?:^|;\\s*)rules=([^;]+)");

    private DisplayNameTransformationRules() {
    }

    public static String encode(Collection<String> rules) {
        Set<String> requested = new LinkedHashSet<>(rules);
        requested.remove(NONE);
        if (!KNOWN_RULES.containsAll(requested)) {
            Set<String> unknown = new LinkedHashSet<>(requested);
            unknown.removeAll(KNOWN_RULES);
            throw new IllegalArgumentException("Unknown display-name transformation rules: " + unknown);
        }
        List<String> ordered = ORDERED_RULES.stream().filter(requested::contains).toList();
        return ordered.isEmpty() ? NONE : String.join("|", ordered);
    }

    public static List<String> decode(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Display-name transformation rules are missing");
        }
        if (NONE.equals(encoded)) {
            return List.of();
        }
        List<String> decoded = new ArrayList<>(List.of(encoded.split("\\|", -1)));
        if (decoded.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Display-name transformation rules contain an empty rule");
        }
        if (new LinkedHashSet<>(decoded).size() != decoded.size()) {
            throw new IllegalArgumentException("Display-name transformation rules contain duplicates");
        }
        if (!KNOWN_RULES.containsAll(decoded)) {
            Set<String> unknown = new LinkedHashSet<>(decoded);
            unknown.removeAll(KNOWN_RULES);
            throw new IllegalArgumentException("Unknown display-name transformation rules: " + unknown);
        }
        String canonical = encode(decoded);
        if (!canonical.equals(encoded)) {
            throw new IllegalArgumentException("Display-name transformation rules are not canonically ordered");
        }
        return List.copyOf(decoded);
    }

    public static String extract(String explanation) {
        Matcher matcher = EXPLANATION_RULES.matcher(explanation == null ? "" : explanation);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    public static List<String> orderedRules() {
        return ORDERED_RULES;
    }
}
