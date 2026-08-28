package de.ixit.gtfs;

import de.ixit.gtfs.model.CanonicalStopArea;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaCity;
import de.ixit.gtfs.model.StopAreaDisplayName;

import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class StopAreaPublicDisplayNameFormatter {
    private static final int MAX_RESOLVED_CITY_PREFIX_STEPS = 4;
    private static final Pattern LEADING_PARENTHESIZED_QUALIFIER = Pattern.compile("^\\(([^)]+)\\)\\s+(.+)$");
    private static final Pattern LEADING_QUALIFIED_CITY = Pattern.compile("^([^()]+?)(\\s*)\\(([^)]+)\\)\\s*(.+)$");
    private static final Pattern STREET_VARIANT = Pattern.compile("(?iu)(?:straße|strasse|str)\\.?(?!\\p{L})");
    private static final Pattern STATION_ABBREVIATION = Pattern.compile("(?iu)(?<!\\p{L})(?:hbf|bf|bhf)\\.?(?!\\p{L})");
    private static final Pattern TRAILING_GENERIC_STATION = Pattern.compile("(?iu)^(.+?)\\s+Bahnhof$");
    private static final Pattern LOCALITY_LIKE_STATION_NAME = Pattern.compile(
            "(?iu)^[\\p{L}\\p{N}][\\p{L}\\p{N}.'()-]*(?:\\s+[\\p{L}\\p{N}][\\p{L}\\p{N}.'()-]*){0,3}$"
    );
    private static final Pattern TECHNICAL_QUALIFIER = Pattern.compile(
            "(?iu)\\((?:oben|tief|upper|lower|s|u|s\\s*\\+?\\s*u|bus|tram)\\)"
    );
    private static final Map<String, String> CITY_CODE_EXPANSIONS = Map.ofEntries(
            Map.entry("D", "Düsseldorf"),
            Map.entry("DO", "Dortmund"),
            Map.entry("DU", "Duisburg"),
            Map.entry("E", "Essen"),
            Map.entry("FFM", "Frankfurt am Main"),
            Map.entry("MG", "Mönchengladbach"),
            Map.entry("OB", "Oberhausen"),
            Map.entry("SZ", "Salzgitter"),
            Map.entry("AB", "Aschaffenburg"),
            Map.entry("PB", "Paderborn"),
            Map.entry("GD", "Schwäbisch Gmünd")
    );

    private StopAreaPublicDisplayNameFormatter() {
    }

    public static StopAreaDisplayName forMember(CanonicalStopArea area, String memberAreaId) {
        return forMember(area, memberAreaId, null);
    }

    public static StopAreaDisplayName forMember(
            CanonicalStopArea area,
            String memberAreaId,
            StopAreaCity resolvedCity
    ) {
        return forMember(area, memberAreaId, resolvedCity, CityPrefixAliasResolver.builtIn());
    }

    public static StopAreaDisplayName forMember(
            CanonicalStopArea area,
            String memberAreaId,
            StopAreaCity resolvedCity,
            CityPrefixAliasResolver prefixResolver
    ) {
        NameParts parts = removeGenericStationSuffix(
                withResolvedCity(
                        partsFor(area),
                        area.canonicalDisplayName(),
                        resolvedCity,
                        prefixResolver
                ),
                area.originalName(),
                area.canonicalDisplayName()
        );
        String displayName = publicDisplayName(parts);
        String transformationRules = transformationRules(
                area.originalName(),
                area.canonicalDisplayName(),
                parts,
                resolvedCity
        );
        String quality = displayName.isBlank() || parts.cityName().isBlank() ? "TECHNICAL" : "GOOD";
        return new StopAreaDisplayName(
                memberAreaId,
                area.canonicalAreaId(),
                displayName,
                StopNameNormalizer.normalize(displayName),
                parts.stopName(),
                parts.cityName(),
                quality,
                displaySource(parts, resolvedCity),
                "canonical_display=" + nullToBlank(area.canonicalDisplayName())
                        + "; original=" + nullToBlank(area.originalName())
                        + "; source=" + parts.source()
                        + "; rules=" + transformationRules
                        + "; output=" + displayName
                        + cityExplanation(resolvedCity)
        );
    }

    public static StopAreaDisplayName forRawStopArea(StopArea area) {
        return forRawStopArea(area, null);
    }

    public static StopAreaDisplayName forRawStopArea(StopArea area, StopAreaCity resolvedCity) {
        return forRawStopArea(area, resolvedCity, CityPrefixAliasResolver.builtIn());
    }

    public static StopAreaDisplayName forRawStopArea(
            StopArea area,
            StopAreaCity resolvedCity,
            CityPrefixAliasResolver prefixResolver
    ) {
        return forConcreteStopArea(area, area.areaId(), resolvedCity, prefixResolver);
    }

    public static StopAreaDisplayName forFamilyMember(StopArea area, String canonicalAreaId) {
        return forFamilyMember(area, canonicalAreaId, null);
    }

    public static StopAreaDisplayName forFamilyMember(
            StopArea area,
            String canonicalAreaId,
            StopAreaCity resolvedCity
    ) {
        return forFamilyMember(area, canonicalAreaId, resolvedCity, CityPrefixAliasResolver.builtIn());
    }

    public static StopAreaDisplayName forFamilyMember(
            StopArea area,
            String canonicalAreaId,
            StopAreaCity resolvedCity,
            CityPrefixAliasResolver prefixResolver
    ) {
        return forConcreteStopArea(area, canonicalAreaId, resolvedCity, prefixResolver);
    }

    private static StopAreaDisplayName forConcreteStopArea(
            StopArea area,
            String canonicalAreaId,
            StopAreaCity resolvedCity,
            CityPrefixAliasResolver prefixResolver
    ) {
        CanonicalStopArea syntheticArea = new CanonicalStopArea(
                area.areaId(),
                area.areaName(),
                area.areaName(),
                "",
                "",
                "RAW_STOP_AREA",
                area.areaId(),
                "",
                false,
                "",
                1,
                "RAW",
                "RAW_STOP_AREA_DISPLAY",
                "raw_stop_area_display"
        );
        NameParts parts = removeGenericStationSuffix(
                withResolvedCity(partsFor(syntheticArea), area.areaName(), resolvedCity, prefixResolver),
                area.areaName()
        );
        String displayName = publicDisplayName(parts);
        String transformationRules = transformationRules(area.areaName(), area.areaName(), parts, resolvedCity);
        String quality = displayName.isBlank() || parts.cityName().isBlank() ? "TECHNICAL" : "GOOD";
        return new StopAreaDisplayName(
                area.areaId(),
                canonicalAreaId,
                displayName.isBlank() ? area.areaName() : displayName,
                StopNameNormalizer.normalize(displayName.isBlank() ? area.areaName() : displayName),
                parts.stopName(),
                parts.cityName(),
                quality,
                displaySource(parts, resolvedCity),
                "raw_stop_area=" + nullToBlank(area.areaName())
                        + "; source=" + parts.source()
                        + "; rules=" + transformationRules
                        + "; output=" + displayName
                        + cityExplanation(resolvedCity)
        );
    }

    public static String publicDisplayName(CanonicalStopArea area) {
        return publicDisplayName(removeGenericStationSuffix(
                partsFor(area),
                area.originalName(),
                area.canonicalDisplayName()
        ));
    }

    private static NameParts partsFor(CanonicalStopArea area) {
        NameParts leadingCode = fromLeadingCityCode(area.canonicalDisplayName());
        if (leadingCode.valid()) {
            return leadingCode;
        }

        NameParts originalParenthesizedCity = fromParenthesizedCity(area.originalName());
        if (originalParenthesizedCity.valid()) {
            return originalParenthesizedCity;
        }

        NameParts canonicalParenthesizedCity = fromParenthesizedCity(area.canonicalDisplayName());
        if (canonicalParenthesizedCity.valid()) {
            return canonicalParenthesizedCity;
        }

        NameParts originalComma = fromCommaName(area.originalName());
        if (originalComma.valid()) {
            return originalComma;
        }

        NameParts canonicalComma = fromCommaName(area.canonicalDisplayName());
        if (canonicalComma.valid()) {
            return canonicalComma;
        }

        NameParts originalStationIntent = fromStationIntentName(area.originalName());
        if (originalStationIntent.valid() && !hasStationIntent(area.canonicalDisplayName())) {
            return originalStationIntent;
        }

        NameParts namedFacility = fromNamedFacility(area.canonicalDisplayName());
        if (!namedFacility.stopName().isBlank()) {
            return namedFacility;
        }

        String fieldCity = cleanCity(area.cityName());
        String fieldStation = cleanStop(area.stationName());
        if (area.hasRailService()
                && !fieldCity.isBlank()
                && sameNormalized(clean(area.canonicalDisplayName()), fieldCity)
                && !hasStationIntent(area.canonicalDisplayName())) {
            return new NameParts("Bahnhof", fieldCity, "RAIL_CITY_FALLBACK");
        }
        if (!fieldCity.isBlank()
                && !fieldStation.isBlank()
                && !sameNormalized(fieldCity, fieldStation)
                && !sameNormalized(clean(area.canonicalDisplayName()), fieldCity)) {
            if (!isSyntheticBareStationFieldWithoutStationIntent(area, fieldStation)) {
                NameParts districtStation = fromDistrictStationFields(area, fieldCity, fieldStation);
                if (districtStation.valid()) {
                    return districtStation;
                }
                return new NameParts(fieldStation, fieldCity, "CANONICAL_FIELDS");
            }
        }

        NameParts stationIntent = fromStationIntentName(area.canonicalDisplayName());
        if (stationIntent.valid()) {
            return stationIntent;
        }

        NameParts trailingCityStation = fromTrailingCityStationName(area.canonicalDisplayName());
        if (trailingCityStation.valid()) {
            return trailingCityStation;
        }

        NameParts generic = fromGenericCityName(area.canonicalDisplayName());
        if (generic.valid()) {
            return generic;
        }

        return new NameParts(cleanStop(area.canonicalDisplayName()), "", "CANONICAL_ONLY");
    }

    private static NameParts fromCommaName(String value) {
        if (value == null || value.isBlank()) {
            return NameParts.invalid();
        }
        String withoutQualifier = value.trim();
        int comma = withoutQualifier.indexOf(',');
        if (comma <= 0 || comma >= withoutQualifier.length() - 1) {
            return NameParts.invalid();
        }
        String left = clean(withoutQualifier.substring(0, comma));
        String right = cleanCity(withoutQualifier.substring(comma + 1));
        if (left.isBlank() || right.isBlank()) {
            return NameParts.invalid();
        }
        if (isModeSuffix(right)) {
            NameParts stationIntent = fromStationIntentName(left);
            if (stationIntent.valid()) {
                return new NameParts(stationIntent.stopName(), stationIntent.cityName(), "MODE_SUFFIX_CITY_STATION");
            }
            NameParts trailingCityStation = fromTrailingCityStationName(left);
            if (trailingCityStation.valid()) {
                return new NameParts(trailingCityStation.stopName(), trailingCityStation.cityName(), "MODE_SUFFIX_TRAILING_CITY");
            }
            return NameParts.invalid();
        }
        String rightWithoutModePrefix = stripLeadingModePrefix(right);
        if (hasStationIntent(left)
                && !rightWithoutModePrefix.equals(right)
                && !rightWithoutModePrefix.isBlank()) {
            return new NameParts(cleanStop(left), cleanCity(rightWithoutModePrefix), "POSTPOSED_MODE_CITY");
        }
        if (hasStationIntent(left) && !hasStationIntent(right)) {
            return new NameParts(cleanStop(left), right, "POSTPOSED_CITY");
        }
        if (!hasStationIntent(left) && hasStationIntent(right)) {
            return new NameParts(
                    cleanStop(stripMatchingCity(right, left)),
                    cleanCity(left),
                    "CITY_COMMA_STATION"
            );
        }
        return NameParts.invalid();
    }

    private static NameParts fromNamedFacility(String value) {
        String cleaned = clean(stripLeadingModePrefix(value));
        String normalized = StopNameNormalizer.normalize(cleaned);
        if (normalized.equals("flughafen") || normalized.startsWith("flughafen ")) {
            return new NameParts(cleanStop(cleaned), "", "NAMED_FACILITY");
        }
        return NameParts.invalid();
    }

    private static NameParts fromTrailingCityStationName(String value) {
        String cleaned = clean(stripLeadingModePrefix(value));
        if (cleaned.isBlank()) {
            return NameParts.invalid();
        }

        String normalized = StopNameNormalizer.normalize(cleaned);
        for (String prefix : new String[]{
                "hauptbahnhof ",
                "hbf ",
                "ostbahnhof ",
                "westbahnhof ",
                "nordbahnhof ",
                "suedbahnhof ",
                "sudbahnhof ",
                "bahnhof "
        }) {
            if (!normalized.startsWith(prefix)) {
                continue;
            }
            String stop = cleanStop(cleaned.substring(0, displayPrefixLength(cleaned, prefix)).trim());
            String city = cleanCity(cleaned.substring(displayPrefixLength(cleaned, prefix)).trim());
            if (!stop.isBlank() && !city.isBlank()) {
                return new NameParts(stop, city, "TRAILING_CITY_STATION");
            }
        }
        return NameParts.invalid();
    }

    private static NameParts fromParenthesizedCity(String value) {
        if (value == null || value.isBlank()) {
            return NameParts.invalid();
        }
        int open = value.lastIndexOf('(');
        int close = value.lastIndexOf(')');
        if (open <= 0 || close <= open + 1) {
            return NameParts.invalid();
        }
        String beforeParentheses = value.substring(0, open).trim();
        if (beforeParentheses.contains(",")) {
            return NameParts.invalid();
        }
        String city = cleanCity(value.substring(open + 1, close));
        String station = cleanStop(stripLeadingModePrefix(beforeParentheses));
        if (city.isBlank()
                || station.isBlank()
                || !hasStationIntent(station)
                || isTechnicalLocationQualifier(city)) {
            return NameParts.invalid();
        }
        return new NameParts(station, city, "PARENTHESIZED_CITY");
    }

    private static NameParts fromLeadingCityCode(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return NameParts.invalid();
        }
        for (Map.Entry<String, String> entry : CITY_CODE_EXPANSIONS.entrySet()) {
            String prefix = entry.getKey();
            if (!cleaned.toUpperCase(Locale.ROOT).startsWith(prefix + "-")) {
                continue;
            }
            String station = cleanStop(cleaned.substring(prefix.length() + 1).trim());
            if (station.isBlank()) {
                return NameParts.invalid();
            }
            return new NameParts(station, entry.getValue(), "LEADING_CITY_CODE");
        }
        String[] parts = cleaned.split(" ");
        if (parts.length < 2) {
            return NameParts.invalid();
        }
        String expandedCity = CITY_CODE_EXPANSIONS.get(parts[0].toUpperCase(Locale.ROOT));
        if (expandedCity == null) {
            return NameParts.invalid();
        }
        String station = cleanStop(cleaned.substring(parts[0].length()).trim());
        if (station.isBlank()) {
            return NameParts.invalid();
        }
        return new NameParts(station, expandedCity, "LEADING_CITY_CODE");
    }

    private static NameParts fromStationIntentName(String value) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) {
            return NameParts.invalid();
        }
        String[] parts = cleaned.split(" ");
        for (int i = 1; i < parts.length; i++) {
            if (isStationToken(parts[i])) {
                String city = cleanCity(join(parts, 0, i));
                String station = cleanStop(join(parts, i, parts.length));
                if (!city.isBlank() && !station.isBlank()) {
                    return new NameParts(station, city, "CITY_STATION_INTENT");
                }
            }
        }
        return NameParts.invalid();
    }

    private static NameParts fromGenericCityName(String value) {
        String cleaned = clean(value);
        String[] parts = cleaned.split(" ");
        if (parts.length < 2) {
            return NameParts.invalid();
        }
        String city = cleanCity(parts[0]);
        String station = cleanStop(cleaned.substring(parts[0].length()).trim());
        if (city.isBlank() || station.isBlank()) {
            return NameParts.invalid();
        }
        return new NameParts(station, city, "LEADING_CITY_GENERIC");
    }

    private static NameParts fromDistrictStationFields(
            CanonicalStopArea area,
            String fieldCity,
            String fieldStation
    ) {
        if (!isBareStationName(fieldStation)) {
            return NameParts.invalid();
        }
        String[] cityParts = fieldCity.split(" ");
        if (cityParts.length < 2 || isMultiWordCityParticle(cityParts[1])) {
            return NameParts.invalid();
        }
        String canonical = clean(area.canonicalDisplayName());
        String original = clean(area.originalName());
        String expected = clean(fieldCity + " " + fieldStation);
        String expectedRaw = clean(fieldCity + " " + clean(area.stationName()));
        if (!sameNormalized(canonical, expected)
                && !sameNormalized(original, expected)
                && !sameNormalized(canonical, expectedRaw)
                && !sameNormalized(original, expectedRaw)) {
            return NameParts.invalid();
        }
        String city = cleanCity(cityParts[0]);
        String district = cleanStop(fieldCity.substring(cityParts[0].length()).trim());
        if (city.isBlank() || district.isBlank()) {
            return NameParts.invalid();
        }
        return new NameParts(cleanStop(district + " " + fieldStation), city, "DISTRICT_STATION_FIELDS");
    }

    private static String publicDisplayName(NameParts parts) {
        if (parts.stopName().isBlank()) {
            return "";
        }
        if (parts.cityName().isBlank()) {
            return parts.stopName();
        }
        return parts.stopName() + ", " + parts.cityName();
    }

    private static NameParts removeGenericStationSuffix(NameParts parts, String... evidenceNames) {
        if (parts.cityName().isBlank()) {
            return parts;
        }
        Matcher matcher = TRAILING_GENERIC_STATION.matcher(parts.stopName());
        if (!matcher.matches()) {
            return parts;
        }
        String designation = cleanStop(matcher.group(1));
        if (containsStreetNameHint(evidenceNames)
                || !isLocalityLikeStationDesignation(designation, parts.cityName())) {
            return parts;
        }
        return new NameParts(
                designation,
                parts.cityName(),
                parts.source() + "+GENERIC_STATION_SUFFIX_REMOVED"
        );
    }

    private static boolean isLocalityLikeStationDesignation(String designation, String cityName) {
        String normalized = StopNameNormalizer.normalize(designation);
        long letterCount = designation.codePoints().filter(Character::isLetter).count();
        if (normalized.isBlank()
                || letterCount < 3
                || sameNormalized(designation, cityName)
                || !LOCALITY_LIKE_STATION_NAME.matcher(designation).matches()) {
            return false;
        }
        for (String rawToken : designation.split("\\s+")) {
            long tokenLetters = rawToken.codePoints().filter(Character::isLetter).count();
            if (tokenLetters <= 3
                    && tokenLetters > 0
                    && rawToken.equals(rawToken.toUpperCase(Locale.ROOT))) {
                return false;
            }
        }
        for (String token : normalized.split(" ")) {
            if (token.length() < 2) {
                return false;
            }
            if (token.matches(".*(?:str|strasse|weg|platz|allee|gasse|ring|chaussee|damm|ufer|bruecke)$")) {
                return false;
            }
            boolean generic = switch (token) {
                case "abzw", "abzweig", "abzweigung", "alt", "alte", "alten", "alter", "altes",
                        "anhalter",
                        "am", "an", "auf", "bei", "busbahnhof", "busstation", "dem", "den", "der",
                        "dorf", "ehem", "ehemalig", "ehemalige", "ehemaliger", "flughafen",
                        "gegenueber", "goerlitzer", "hinter", "holzkirchner", "im", "in", "naehe",
                        "nord", "oberer", "ost", "post", "richtung", "schweizer", "sued", "unter",
                        "unterer", "vor", "west", "zob", "zum", "zur", "bayerischer" -> true;
                default -> false;
            };
            if (generic) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsStreetNameHint(String... evidenceNames) {
        for (String evidenceName : evidenceNames) {
            if (STREET_VARIANT.matcher(nullToBlank(evidenceName)).find()) {
                return true;
            }
        }
        return false;
    }

    private static NameParts withResolvedCity(
            NameParts inferred,
            String sourceName,
            StopAreaCity resolvedCity,
            CityPrefixAliasResolver prefixResolver
    ) {
        if ("NAMED_FACILITY".equals(inferred.source())) {
            return inferred;
        }
        if (resolvedCity == null || resolvedCity.cityName() == null || resolvedCity.cityName().isBlank()) {
            return inferred;
        }
        String city = cleanCity(resolvedCity.cityName());
        if (city.isBlank()) {
            return inferred;
        }
        if (hasStationIntent(inferred.stopName()) && sameNormalized(inferred.cityName(), city)) {
            ResolvedCityName normalizedInferred = normalizeResolvedCityName(
                    inferred.stopName(),
                    city,
                    prefixResolver
            );
            String inferredStop = cleanStop(normalizedInferred.stopName());
            if (!inferredStop.isBlank() && !sameNormalized(inferredStop, city)) {
                return new NameParts(
                        inferredStop,
                        city,
                        normalizedInferred.changed() ? normalizedInferred.source() : inferred.source()
                );
            }
        }
        ResolvedCityName normalized = normalizeResolvedCityName(sourceName, city, prefixResolver);
        if (normalized.changed()) {
            String prefixedStop = cleanStop(normalized.stopName());
            if (!prefixedStop.isBlank() && !sameNormalized(prefixedStop, city)) {
                return new NameParts(prefixedStop, city, normalized.source());
            }
        }
        ResolvedCityName normalizedInferred = normalizeResolvedCityName(
                inferred.stopName(),
                city,
                prefixResolver
        );
        if (normalizedInferred.changed()) {
            String inferredStop = cleanStop(normalizedInferred.stopName());
            if (!inferredStop.isBlank() && !sameNormalized(inferredStop, city)) {
                return new NameParts(inferredStop, city, normalizedInferred.source());
            }
        }
        if ("LEADING_CITY_GENERIC".equals(inferred.source())
                && !sameNormalized(inferred.cityName(), city)) {
            String localityStop = cleanStop(stripLeadingModePrefix(sourceName));
            if (!localityStop.isBlank()
                    && !sameNormalized(localityStop, city)
                    && !hasRepeatedLeadingToken(localityStop)) {
                return new NameParts(localityStop, city, "RESOLVED_CITY_LOCALITY_PRESERVED");
            }
        }
        String stop = stopNameForResolvedCity(sourceName, city, inferred);
        if (stop.isBlank() || sameNormalized(stop, city)) {
            stop = inferred.stopName();
        }
        if (stop.isBlank() || sameNormalized(stop, city)) {
            return inferred;
        }
        return new NameParts(stop, city, inferred.source());
    }

    private static ResolvedCityName normalizeResolvedCityName(
            String sourceName,
            String city,
            CityPrefixAliasResolver prefixResolver
    ) {
        String current = clean(sourceName);
        StringJoiner sources = new StringJoiner("+");
        boolean changed = false;

        for (int step = 0; step < MAX_RESOLVED_CITY_PREFIX_STEPS; step++) {
            String withoutQualifiedCity = stripLeadingQualifiedCity(current, city);
            if (!withoutQualifiedCity.equals(current) && !withoutQualifiedCity.isBlank()) {
                current = clean(withoutQualifiedCity);
                sources.add("CITY_QUALIFIED_NAME");
                changed = true;
                continue;
            }

            String withoutCity = stripMatchingCity(current, city);
            if (!withoutCity.equals(current) && !withoutCity.isBlank()) {
                current = clean(withoutCity);
                sources.add("CITY_NAME");
                changed = true;
                continue;
            }

            CityPrefixAliasResolver.PrefixMatch prefixMatch = prefixResolver.stripLeadingPrefix(current, city);
            if (prefixMatch.stripped() && !prefixMatch.stopName().isBlank()) {
                current = clean(prefixMatch.stopName());
                sources.add(prefixMatch.source());
                changed = true;
                continue;
            }

            String withoutQualifier = stripLeadingMatchingCityQualifier(current, city);
            if (!withoutQualifier.equals(current) && !withoutQualifier.isBlank()) {
                current = clean(withoutQualifier);
                sources.add("CITY_QUALIFIER");
                changed = true;
                continue;
            }
            break;
        }

        String source = changed ? "CITY_PREFIX_CHAIN_" + sources : "NONE";
        return new ResolvedCityName(current, source, changed);
    }

    private static String stripLeadingQualifiedCity(String value, String city) {
        String cleaned = clean(value);
        Matcher matcher = LEADING_QUALIFIED_CITY.matcher(cleaned);
        if (!matcher.matches()) {
            return cleaned;
        }
        String base = StopNameNormalizer.normalize(matcher.group(1));
        String separator = matcher.group(2);
        String qualifier = StopNameNormalizer.normalize(matcher.group(3));
        String normalizedCity = StopNameNormalizer.normalize(city);
        boolean matchingBase = !base.isBlank()
                && (normalizedCity.equals(base) || normalizedCity.startsWith(base + " "));
        boolean matchingQualifier = qualifier.length() >= 3
                && (" " + normalizedCity + " ").contains(" " + qualifier + " ");
        boolean attachedCityQualifier = separator.isEmpty() && normalizedCity.equals(base);
        return matchingBase && (matchingQualifier || attachedCityQualifier)
                ? clean(matcher.group(4).replaceFirst("^[\\s,./-]+", ""))
                : cleaned;
    }

    private static boolean hasRepeatedLeadingToken(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        String[] tokens = normalized.split(" ");
        return tokens.length > 1 && tokens[0].equals(tokens[1]);
    }

    private static String stripLeadingMatchingCityQualifier(String value, String city) {
        String cleaned = clean(value);
        Matcher matcher = LEADING_PARENTHESIZED_QUALIFIER.matcher(cleaned);
        if (!matcher.matches()) {
            return cleaned;
        }
        String qualifier = StopNameNormalizer.normalize(matcher.group(1));
        String normalizedCity = StopNameNormalizer.normalize(city);
        if (qualifier.length() < 3
                || !(" " + normalizedCity + " ").contains(" " + qualifier + " ")) {
            return cleaned;
        }
        return clean(matcher.group(2));
    }

    private static String stopNameForResolvedCity(String sourceName, String city, NameParts inferred) {
        String cleaned = clean(stripLeadingModePrefix(sourceName));
        if (cleaned.isBlank()) {
            return inferred.stopName();
        }
        String cityPattern = Pattern.quote(city);
        String withoutLeadingCity = cleaned.replaceFirst("(?iu)^" + cityPattern + "(?:[\\s,/-]+)", "").trim();
        if (!withoutLeadingCity.equals(cleaned) && !withoutLeadingCity.isBlank()) {
            return cleanStop(withoutLeadingCity);
        }
        String withoutTrailingCity = cleaned.replaceFirst("(?iu)(?:[\\s,/-]+)" + cityPattern + "$", "").trim();
        if (!withoutTrailingCity.equals(cleaned) && !withoutTrailingCity.isBlank()) {
            return cleanStop(withoutTrailingCity);
        }
        if (!inferred.stopName().isBlank() && !sameNormalized(inferred.stopName(), inferred.cityName())) {
            return inferred.stopName();
        }
        return cleanStop(cleaned);
    }

    private static String stripMatchingCity(String value, String city) {
        String cleaned = clean(value);
        String cleanedCity = cleanCity(city);
        if (cleaned.isBlank() || cleanedCity.isBlank()) {
            return cleaned;
        }
        String cityPattern = Pattern.quote(cleanedCity);
        String withoutLeadingCity = cleaned
                .replaceFirst("(?iu)^" + cityPattern + "(?:[\\s,/-]+)", "")
                .trim();
        if (!withoutLeadingCity.equals(cleaned) && !withoutLeadingCity.isBlank()) {
            return withoutLeadingCity;
        }
        String withoutNormalizedLeadingCity = stripNormalizedLeadingCity(cleaned, cleanedCity);
        if (!withoutNormalizedLeadingCity.equals(cleaned) && !withoutNormalizedLeadingCity.isBlank()) {
            return withoutNormalizedLeadingCity;
        }
        String withoutTrailingCity = cleaned
                .replaceFirst("(?iu)(?:[\\s,/-]+)" + cityPattern + "$", "")
                .trim();
        if (!withoutTrailingCity.equals(cleaned) && !withoutTrailingCity.isBlank()) {
            return withoutTrailingCity;
        }
        String withoutNormalizedTrailingCity = stripNormalizedTrailingCity(cleaned, cleanedCity);
        return withoutNormalizedTrailingCity.isBlank() ? cleaned : withoutNormalizedTrailingCity;
    }

    private static String stripNormalizedLeadingCity(String value, String city) {
        String normalizedCity = StopNameNormalizer.normalize(city);
        for (int index = 1; index < value.length(); index++) {
            if (!isCityBoundary(value.charAt(index))) {
                continue;
            }
            String candidate = value.substring(0, index).trim();
            if (!StopNameNormalizer.normalize(candidate).equals(normalizedCity)) {
                continue;
            }
            String remainder = value.substring(index).replaceFirst("^[\\s,/-]+", "").trim();
            return remainder.isBlank() ? value : remainder;
        }
        return value;
    }

    private static String stripNormalizedTrailingCity(String value, String city) {
        String normalizedCity = StopNameNormalizer.normalize(city);
        for (int index = value.length() - 1; index > 0; index--) {
            if (!isCityBoundary(value.charAt(index - 1))) {
                continue;
            }
            String candidate = value.substring(index).trim();
            if (!StopNameNormalizer.normalize(candidate).equals(normalizedCity)) {
                continue;
            }
            String remainder = value.substring(0, index).replaceFirst("[\\s,/-]+$", "").trim();
            return remainder.isBlank() ? value : remainder;
        }
        return value;
    }

    private static boolean isCityBoundary(char value) {
        return Character.isWhitespace(value) || value == ',' || value == '/' || value == '-';
    }

    private static String displaySource(NameParts parts, StopAreaCity resolvedCity) {
        if (resolvedCity == null || resolvedCity.cityName() == null || resolvedCity.cityName().isBlank()) {
            return parts.source();
        }
        return parts.source() + "+" + resolvedCity.source();
    }

    private static String cityExplanation(StopAreaCity resolvedCity) {
        if (resolvedCity == null) {
            return "";
        }
        return "; city=" + nullToBlank(resolvedCity.cityName())
                + "; city_source=" + nullToBlank(resolvedCity.source())
                + "; city_quality=" + nullToBlank(resolvedCity.quality())
                + "; city_data_version=" + nullToBlank(resolvedCity.dataVersion());
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("/", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanCity(String value) {
        String cleaned = clean(value).replaceAll("\\s*\\([^)]*\\)", "").trim();
        if (cleaned.isBlank()) {
            return "";
        }
        String expanded = CITY_CODE_EXPANSIONS.get(cleaned.toUpperCase(Locale.ROOT));
        return expanded == null ? cleaned : expanded;
    }

    private static String cleanStop(String value) {
        String cleaned = clean(value)
                .replaceAll("(?iu)\\s*\\((?:oben|tief|upper|lower|s|u|s\\s*\\+?\\s*u|bus|tram)\\)\\s*", " ")
                .replaceAll("(?i)\\bhbf\\b\\.*", "Hauptbahnhof")
                .replaceAll("(?i)\\bbf\\b\\.*", "Bahnhof")
                .replaceAll("(?i)\\bbhf\\b\\.*", "Bahnhof")
                .replaceAll("\\s+", " ")
                .trim();
        cleaned = normalizeStreetVariants(cleaned);
        return cleaned
                .replace("hauptbahnhof", "Hauptbahnhof")
                .replace("HBF", "Hauptbahnhof")
                .trim();
    }

    private static String normalizeStreetVariants(String value) {
        return STREET_VARIANT.matcher(value).replaceAll(match -> {
            if (match.start() == 0) {
                return "Str.";
            }
            int previousCodePoint = value.codePointBefore(match.start());
            return Character.isLetterOrDigit(previousCodePoint) ? "str." : "Str.";
        });
    }

    private static String transformationRules(
            String originalName,
            String canonicalName,
            NameParts parts,
            StopAreaCity resolvedCity
    ) {
        Set<String> rules = new LinkedHashSet<>();
        String source = nullToBlank(parts.source());
        if (resolvedCity != null && resolvedCity.cityName() != null && !resolvedCity.cityName().isBlank()) {
            rules.add(DisplayNameTransformationRules.CITY_FROM_RESOLVED_CONTEXT);
        }
        if (source.contains("LEADING_CITY_CODE")
                || hasLeadingKnownCityCode(originalName)
                || hasLeadingKnownCityCode(canonicalName)) {
            rules.add(DisplayNameTransformationRules.CITY_CODE_EXPANDED);
            rules.add(DisplayNameTransformationRules.CITY_PREFIX_REMOVED);
        }
        if (source.contains("CITY_PREFIX_CHAIN")
                || source.contains("CITY_NAME")
                || source.contains("CITY_ALIAS")) {
            rules.add(DisplayNameTransformationRules.CITY_PREFIX_REMOVED);
        }
        if (source.contains("CITY_QUALIFIER") || source.contains("CITY_QUALIFIED_NAME")) {
            rules.add(DisplayNameTransformationRules.CITY_QUALIFIER_REMOVED);
        }
        if (source.contains("RESOLVED_CITY_LOCALITY_PRESERVED")) {
            rules.add(DisplayNameTransformationRules.LOCALITY_COMPOUND_PRESERVED);
        }
        if (changedByModePrefixRemoval(originalName) || changedByModePrefixRemoval(canonicalName)) {
            rules.add(DisplayNameTransformationRules.MODE_PREFIX_REMOVED);
        }
        if (containsTechnicalQualifier(originalName) || containsTechnicalQualifier(canonicalName)) {
            rules.add(DisplayNameTransformationRules.TECHNICAL_QUALIFIER_REMOVED);
        }
        if (containsStationAbbreviation(originalName) || containsStationAbbreviation(canonicalName)) {
            rules.add(DisplayNameTransformationRules.STATION_ABBREVIATION_EXPANDED);
        }
        if (source.contains("GENERIC_STATION_SUFFIX_REMOVED")) {
            rules.add(DisplayNameTransformationRules.GENERIC_STATION_SUFFIX_REMOVED);
        }
        if (changedByStreetNormalization(originalName) || changedByStreetNormalization(canonicalName)) {
            rules.add(DisplayNameTransformationRules.STREET_SUFFIX_NORMALIZED);
        }
        if (!parts.cityName().isBlank()) {
            rules.add(DisplayNameTransformationRules.STOP_CITY_COMPOSED);
        }
        return DisplayNameTransformationRules.encode(rules);
    }

    private static boolean hasLeadingKnownCityCode(String value) {
        String cleaned = clean(value).toUpperCase(Locale.ROOT);
        return CITY_CODE_EXPANSIONS.keySet().stream()
                .anyMatch(code -> cleaned.startsWith(code + "-") || cleaned.startsWith(code + " "));
    }

    private static boolean changedByModePrefixRemoval(String value) {
        String cleaned = clean(value);
        return !cleaned.isBlank() && !stripLeadingModePrefix(cleaned).equals(cleaned);
    }

    private static boolean containsTechnicalQualifier(String value) {
        return TECHNICAL_QUALIFIER.matcher(nullToBlank(value)).find();
    }

    private static boolean containsStationAbbreviation(String value) {
        return STATION_ABBREVIATION.matcher(nullToBlank(value)).find();
    }

    private static boolean changedByStreetNormalization(String value) {
        String cleaned = clean(value);
        return !cleaned.isBlank() && !normalizeStreetVariants(cleaned).equals(cleaned);
    }

    private static String stripLeadingModePrefix(String value) {
        String cleaned = clean(value);
        return cleaned
                .replaceAll("(?i)^(s\\+u|s u|s\\+u-bahnhof|s-bahnhof|u-bahnhof|s|u)\\s+", "")
                .trim();
    }

    private static boolean isModeSuffix(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return switch (normalized) {
            case "s", "u", "su", "s u", "s und u", "s bahn", "u bahn", "stadtbahn", "bus", "tram" -> true;
            default -> false;
        };
    }

    private static boolean isTechnicalLocationQualifier(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return switch (normalized) {
            case "oben", "tief", "upper", "lower", "s", "u", "s u", "su", "bus", "tram" -> true;
            default -> false;
        };
    }

    private static int displayPrefixLength(String cleaned, String normalizedPrefix) {
        String[] normalizedTokens = normalizedPrefix.trim().split(" ");
        String[] displayTokens = cleaned.split(" ");
        if (displayTokens.length < normalizedTokens.length) {
            return cleaned.length();
        }
        int length = 0;
        for (int i = 0; i < normalizedTokens.length; i++) {
            if (i > 0) {
                length++;
            }
            length += displayTokens[i].length();
        }
        return Math.min(length, cleaned.length());
    }

    private static boolean hasStationIntent(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return containsToken(normalized, "hbf")
                || normalized.contains("hauptbahnhof")
                || normalized.contains("bahnhof")
                || containsToken(normalized, "bf")
                || normalized.contains("ostbahnhof")
                || normalized.contains("westbahnhof")
                || normalized.contains("nordbahnhof")
                || normalized.contains("suedbahnhof")
                || normalized.contains("sudbahnhof");
    }

    private static boolean isStationToken(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return containsToken(normalized, "hbf")
                || normalized.contains("hauptbahnhof")
                || normalized.contains("bahnhof")
                || containsToken(normalized, "bf")
                || normalized.contains("ostbahnhof")
                || normalized.contains("westbahnhof")
                || normalized.contains("nordbahnhof")
                || normalized.contains("suedbahnhof")
                || normalized.contains("sudbahnhof");
    }

    private static boolean isBareStationName(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return "bahnhof".equals(normalized) || "bf".equals(normalized);
    }

    private static boolean isSyntheticBareStationFieldWithoutStationIntent(CanonicalStopArea area, String fieldStation) {
        return isBareStationName(fieldStation)
                && !hasStationIntent(area.canonicalDisplayName())
                && !hasStationIntent(area.originalName());
    }

    private static boolean isMultiWordCityParticle(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return switch (normalized) {
            case "am", "an", "auf", "bei", "der", "dem", "den", "im", "in", "ob", "unter", "vor" -> true;
            default -> false;
        };
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static String join(String[] parts, int fromInclusive, int toExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    private static boolean sameNormalized(String first, String second) {
        return StopNameNormalizer.normalize(first).equals(StopNameNormalizer.normalize(second));
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private record NameParts(String stopName, String cityName, String source) {
        private static NameParts invalid() {
            return new NameParts("", "", "");
        }

        private boolean valid() {
            return !stopName.isBlank() && !cityName.isBlank();
        }
    }

    private record ResolvedCityName(String stopName, String source, boolean changed) {
    }
}
