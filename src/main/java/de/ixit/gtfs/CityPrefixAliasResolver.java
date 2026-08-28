package de.ixit.gtfs;

import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaCity;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Resolves feed-specific city abbreviations at the start of public stop names. */
public final class CityPrefixAliasResolver {
    private static final int MIN_LEARNED_SUPPORT = 20;
    private static final double MIN_LEARNED_DOMINANCE = 0.98;
    private static final Set<String> MODE_CODES = Set.of("S", "U", "SU", "BUS", "TRAM");
    private static final Map<String, String> CURATED_CITY_BY_CODE = curatedCityCodes();
    private static final Map<String, String> CURATED_CITY_BY_WORD_PREFIX = Map.of(
            "FRANKFURT", "Frankfurt am Main"
    );

    private final Set<AliasKey> aliases;
    private final Set<AliasKey> learnedAliases;

    private CityPrefixAliasResolver(Set<AliasKey> aliases, Set<AliasKey> learnedAliases) {
        this.aliases = Set.copyOf(aliases);
        this.learnedAliases = Set.copyOf(learnedAliases);
    }

    public static CityPrefixAliasResolver builtIn() {
        Builder builder = builder();
        return builder.build();
    }

    public static CityPrefixAliasResolver build(List<StopArea> stopAreas, List<StopAreaCity> cities) {
        Map<String, String> cityByAreaId = new HashMap<>();
        for (StopAreaCity city : cities) {
            cityByAreaId.put(city.areaId(), city.cityName());
        }
        Builder builder = builder();
        for (StopArea area : stopAreas) {
            builder.observe(area.areaName(), cityByAreaId.get(area.areaId()));
        }
        return builder.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public PrefixMatch stripLeadingPrefix(String value, String cityName) {
        String cleaned = clean(value);
        String cityKey = cityKey(cityName);
        if (cleaned.isBlank() || cityKey.isBlank()) {
            return PrefixMatch.unchanged(cleaned);
        }

        PrefixCandidate best = null;
        int scanLimit = Math.min(cleaned.length(), 14);
        for (int index = 0; index < scanLimit; index++) {
            if (cleaned.charAt(index) != '-') {
                continue;
            }
            String rawPrefix = cleaned.substring(0, index).trim();
            String code = prefixCode(rawPrefix);
            AliasKey key = new AliasKey(code, cityKey);
            if (code.isBlank() || !isAcceptedPrefix(rawPrefix, key)) {
                continue;
            }
            String remainder = cleaned.substring(index + 1).trim();
            if (!remainder.isBlank()) {
                best = new PrefixCandidate(index, rawPrefix, remainder, learnedAliases.contains(key));
            }
        }

        int firstSpace = cleaned.indexOf(' ');
        if (firstSpace > 0 && firstSpace < scanLimit) {
            String rawPrefix = cleaned.substring(0, firstSpace).trim();
            String code = prefixCode(rawPrefix);
            AliasKey key = new AliasKey(code, cityKey);
            if (isAcceptedPrefix(rawPrefix, key)) {
                String remainder = cleaned.substring(firstSpace + 1).trim();
                if (!remainder.isBlank() && (best == null || firstSpace > best.endIndex())) {
                    best = new PrefixCandidate(firstSpace, rawPrefix, remainder, learnedAliases.contains(key));
                }
            }
        }

        if (best == null) {
            return PrefixMatch.unchanged(cleaned);
        }
        return new PrefixMatch(
                best.remainder(),
                best.rawPrefix(),
                best.learned() ? "LEARNED" : "CURATED_OR_DERIVED",
                true
        );
    }

    public int learnedAliasCount() {
        return learnedAliases.size();
    }

    private static Map<String, String> curatedCityCodes() {
        Map<String, String> codes = new LinkedHashMap<>();
        codes.put("D", "Düsseldorf");
        codes.put("DO", "Dortmund");
        codes.put("DU", "Duisburg");
        codes.put("E", "Essen");
        codes.put("FFM", "Frankfurt am Main");
        codes.put("MG", "Mönchengladbach");
        codes.put("OB", "Oberhausen");
        codes.put("SZ", "Salzgitter");
        codes.put("AB", "Aschaffenburg");
        codes.put("PB", "Paderborn");
        codes.put("GD", "Schwäbisch Gmünd");
        return Map.copyOf(codes);
    }

    private boolean isAcceptedPrefix(String rawPrefix, AliasKey key) {
        if (!aliases.contains(key)) {
            return false;
        }
        if (isPrefixShape(rawPrefix)) {
            return true;
        }
        String curatedCity = CURATED_CITY_BY_WORD_PREFIX.get(prefixCode(rawPrefix));
        return curatedCity != null && cityKey(curatedCity).equals(key.city());
    }

    private static void addDerivedAliases(Set<AliasKey> aliases, String cityName) {
        String city = StopNameNormalizer.normalize(cityName);
        if (city.isBlank()) {
            return;
        }
        String compact = city.replace(" ", "");
        if (compact.length() >= 2) {
            aliases.add(new AliasKey(compact.substring(0, 2).toUpperCase(Locale.ROOT), city));
        }
        String[] words = city.split(" ");
        if (words.length >= 2) {
            StringBuilder initials = new StringBuilder();
            for (String word : words) {
                if (!word.isBlank()) {
                    initials.append(Character.toUpperCase(word.charAt(0)));
                }
            }
            if (initials.length() >= 2) {
                aliases.add(new AliasKey(initials.toString(), city));
            }
        }
    }

    private static String firstPrefixCode(String value) {
        String cleaned = clean(value);
        int dash = cleaned.indexOf('-');
        if (dash <= 0 || dash > 6) {
            return "";
        }
        String prefix = cleaned.substring(0, dash).trim();
        return isPrefixShape(prefix) ? prefixCode(prefix) : "";
    }

    private static boolean isPrefixShape(String value) {
        if (value == null || value.isBlank() || value.length() > 10) {
            return false;
        }
        boolean hasLetter = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isLetter(character)) {
                hasLetter = true;
                if (!Character.isUpperCase(character)) {
                    return false;
                }
            } else if (character != '.' && character != '-') {
                return false;
            }
        }
        return hasLetter;
    }

    private static String prefixCode(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[^\\p{L}]", "").toUpperCase(Locale.ROOT);
    }

    private static String cityKey(String value) {
        return StopNameNormalizer.normalize(value);
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    public record PrefixMatch(String stopName, String prefix, String source, boolean stripped) {
        private static PrefixMatch unchanged(String value) {
            return new PrefixMatch(value, "", "NONE", false);
        }
    }

    public static final class Builder {
        private final Map<String, Map<String, Integer>> observationsByCode = new HashMap<>();
        private final Set<String> observedCities = new HashSet<>();

        private Builder() {
        }

        public Builder observe(String stopName, String cityName) {
            String city = cityKey(cityName);
            String code = firstPrefixCode(stopName);
            if (city.isBlank()) {
                return this;
            }
            observedCities.add(cityName);
            if (code.length() < 2 || MODE_CODES.contains(code)) {
                return this;
            }
            observationsByCode
                    .computeIfAbsent(code, ignored -> new HashMap<>())
                    .merge(city, 1, Integer::sum);
            return this;
        }

        public CityPrefixAliasResolver build() {
            Set<AliasKey> accepted = new HashSet<>();
            for (Map.Entry<String, String> entry : CURATED_CITY_BY_CODE.entrySet()) {
                accepted.add(new AliasKey(entry.getKey(), cityKey(entry.getValue())));
            }
            for (Map.Entry<String, String> entry : CURATED_CITY_BY_WORD_PREFIX.entrySet()) {
                accepted.add(new AliasKey(entry.getKey(), cityKey(entry.getValue())));
            }
            for (String city : observedCities) {
                addDerivedAliases(accepted, city);
            }

            Set<AliasKey> learned = new HashSet<>();
            for (Map.Entry<String, Map<String, Integer>> entry : observationsByCode.entrySet()) {
                String code = entry.getKey();
                int total = entry.getValue().values().stream().mapToInt(Integer::intValue).sum();
                Map.Entry<String, Integer> winner = entry.getValue().entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .orElseThrow();
                double dominance = total == 0 ? 0.0 : (double) winner.getValue() / total;
                if (winner.getValue() >= MIN_LEARNED_SUPPORT
                        && dominance >= MIN_LEARNED_DOMINANCE
                        && isPlausibleCityCode(code, winner.getKey())) {
                    AliasKey key = new AliasKey(code, winner.getKey());
                    if (accepted.add(key)) {
                        learned.add(key);
                    }
                }
            }
            return new CityPrefixAliasResolver(accepted, learned);
        }

        private static boolean isPlausibleCityCode(String code, String city) {
            String compactCity = city.replace(" ", "").toUpperCase(Locale.ROOT);
            int cityIndex = 0;
            for (int codeIndex = 0; codeIndex < code.length(); codeIndex++) {
                cityIndex = compactCity.indexOf(code.charAt(codeIndex), cityIndex);
                if (cityIndex < 0) {
                    return false;
                }
                cityIndex++;
            }
            return true;
        }
    }

    private record AliasKey(String code, String city) {
    }

    private record PrefixCandidate(int endIndex, String rawPrefix, String remainder, boolean learned) {
    }
}
