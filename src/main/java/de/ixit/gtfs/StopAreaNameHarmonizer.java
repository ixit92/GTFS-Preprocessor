package de.ixit.gtfs;

import de.ixit.gtfs.model.CanonicalStopAreaName;
import de.ixit.gtfs.model.StopAreaProfile;

import java.util.ArrayList;
import java.util.Locale;

public final class StopAreaNameHarmonizer {
    private StopAreaNameHarmonizer() {
    }

    public static CanonicalStopAreaName harmonize(
            String canonicalAreaId,
            String originalName,
            StopAreaProfile profile,
            String preferredDisplayName,
            String source
    ) {
        return harmonize(canonicalAreaId, originalName, profile, preferredDisplayName, source, "");
    }

    public static CanonicalStopAreaName harmonize(
            String canonicalAreaId,
            String originalName,
            StopAreaProfile profile,
            String preferredDisplayName,
            String source,
            String officialCityName
    ) {
        String original = nullToBlank(originalName).trim();
        String candidate = cleanDisplayName(firstNonBlank(preferredDisplayName, original));
        String city = cityName(original);
        if (city.isBlank()) {
            city = cityName(candidate);
        }

        boolean stationFamily = profile != null
                && (profile.mainStationSignal()
                || profile.stationNameSignal()
                || profile.hasRailService());
        String normalizedOriginal = StopNameNormalizer.normalize(original);
        String normalizedCandidate = StopNameNormalizer.normalize(candidate);

        if (stationFamily && (isTechnicalDisplayName(candidate) || candidate.isBlank())) {
            candidate = displayFromCityAndStationIntent(city, normalizedOriginal);
        } else if (stationFamily && shouldPreferShortMainStationName(normalizedOriginal, normalizedCandidate, city)) {
            candidate = city + " Hbf";
        } else if (stationFamily && isBahnhofStreetOrBusName(normalizedCandidate)) {
            candidate = displayFromCityAndStationIntent(city, normalizedOriginal);
        }

        candidate = canonicalizeStationWords(candidate);
        if (candidate.isBlank()) {
            candidate = canonicalizeStationWords(cleanDisplayName(original));
        }

        String finalCity = firstNonBlank(cleanDisplayName(officialCityName), cityName(candidate));
        String stationName = stationName(candidate, finalCity);
        String displayQuality = displayQuality(candidate);
        return new CanonicalStopAreaName(
                canonicalAreaId,
                original,
                candidate,
                StopNameNormalizer.normalize(candidate),
                finalCity,
                stationName,
                finalCity.isBlank() ? "STATION_ONLY" : "CITY_STATION",
                displayQuality,
                source,
                explanation(original, candidate, displayQuality)
        );
    }

    public static String cleanDisplayName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return canonicalizeStationWords(name
                .replaceAll("\\s*\\([^)]*\\)", "")
                .replace("/", " ")
                .replace(",", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    public static String cityName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String withoutQualifier = name.replaceAll("\\s*\\([^)]*\\)", "").trim();
        int comma = withoutQualifier.indexOf(',');
        if (comma > 0) {
            String left = cleanDisplayName(withoutQualifier.substring(0, comma));
            String right = stripLeadingModePrefix(cleanDisplayName(withoutQualifier.substring(comma + 1)));
            if (hasStationIntent(left) && !right.isBlank()) {
                return right;
            }
            return left;
        }

        String cleaned = stripLeadingModePrefix(cleanDisplayName(withoutQualifier));
        String[] parts = cleaned.split(" ");
        if (parts.length > 1 && hasStationIntent(parts[0])) {
            return String.join(" ", java.util.Arrays.copyOfRange(parts, 1, parts.length)).trim();
        }
        ArrayList<String> cityParts = new ArrayList<>();
        for (String part : parts) {
            String normalized = StopNameNormalizer.normalize(part);
            if (normalized.isBlank() || isDisplayStopWord(normalized) || normalized.contains("bf")) {
                break;
            }
            cityParts.add(part);
        }
        return String.join(" ", cityParts).trim();
    }

    private static String stripLeadingModePrefix(String value) {
        return value
                .replaceFirst("(?i)^(s\\+u|s u|s\\+u-bahnhof|s-bahnhof|u-bahnhof|s|u)\\s+", "")
                .trim();
    }

    private static boolean hasStationIntent(String value) {
        String normalized = StopNameNormalizer.normalize(value);
        return containsToken(normalized, "hbf")
                || normalized.contains("hauptbahnhof")
                || normalized.contains("bahnhof")
                || containsToken(normalized, "bf");
    }

    public static String stationName(String displayName, String cityName) {
        if (displayName == null || displayName.isBlank()) {
            return "";
        }
        String cleanedDisplayName = stripLeadingModePrefix(cleanDisplayName(displayName));
        if (hasLeadingCityAtBoundary(cleanedDisplayName, cityName)) {
            return stripLeadingCitySeparator(cleanedDisplayName.substring(cityName.length()));
        }
        return cleanedDisplayName;
    }

    private static String stripLeadingCitySeparator(String stationName) {
        String trimmed = stationName.trim();
        return trimmed.startsWith(".") ? trimmed.substring(1).stripLeading() : trimmed;
    }

    private static boolean hasLeadingCityAtBoundary(String displayName, String cityName) {
        if (cityName == null
                || cityName.isBlank()
                || displayName.length() < cityName.length()
                || !displayName.regionMatches(true, 0, cityName, 0, cityName.length())) {
            return false;
        }
        if (displayName.length() == cityName.length()) {
            return true;
        }
        char boundary = displayName.charAt(cityName.length());
        return Character.isWhitespace(boundary)
                || boundary == ','
                || boundary == '/'
                || boundary == '-'
                || boundary == '.'
                || boundary == '(';
    }

    public static String displayQuality(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "TECHNICAL";
        }
        String normalized = StopNameNormalizer.normalize(displayName);
        if (isTechnicalDisplayName(displayName)) {
            return "TECHNICAL";
        }
        if (normalized.contains("zob") || normalized.contains("bus")) {
            return "AVOID_FOR_USER";
        }
        return "GOOD";
    }

    public static boolean isTechnicalDisplayName(String displayName) {
        String normalized = StopNameNormalizer.normalize(displayName);
        return normalized.contains("kaiserstrasse")
                || normalized.contains("bahnhofstrasse")
                || normalized.contains("kirchenallee")
                || normalized.contains("steintordamm")
                || containsToken(normalized, "str")
                || containsToken(normalized, "strasse")
                || normalized.contains("zob")
                || normalized.contains("bus");
    }

    private static String displayFromCityAndStationIntent(String city, String normalizedOriginal) {
        if (city.isBlank()) {
            return "";
        }
        if (containsToken(normalizedOriginal, "hbf") || normalizedOriginal.contains("hauptbahnhof")) {
            return city + " Hbf";
        }
        if (normalizedOriginal.contains("ostbahnhof")) {
            return city + " Ostbahnhof";
        }
        if (normalizedOriginal.contains("westbahnhof")) {
            return city + " Westbahnhof";
        }
        if (normalizedOriginal.contains("nordbahnhof")) {
            return city + " Nordbahnhof";
        }
        if (normalizedOriginal.contains("suedbahnhof") || normalizedOriginal.contains("sudbahnhof")) {
            return city + " Suedbahnhof";
        }
        return city + " Bahnhof";
    }

    private static boolean shouldPreferShortMainStationName(String normalizedOriginal, String normalizedCandidate, String city) {
        if (city.isBlank()) {
            return false;
        }
        return (containsToken(normalizedOriginal, "hbf") || normalizedOriginal.contains("hauptbahnhof"))
                && !containsToken(normalizedCandidate, "hbf");
    }

    private static boolean isBahnhofStreetOrBusName(String normalizedCandidate) {
        return normalizedCandidate.contains("bahnhofstr")
                || normalizedCandidate.contains("kaiserstr")
                || normalizedCandidate.contains("kirchenallee")
                || normalizedCandidate.contains("steintordamm")
                || containsToken(normalizedCandidate, "str")
                || containsToken(normalizedCandidate, "strasse")
                || normalizedCandidate.contains("zob")
                || normalizedCandidate.contains("bus");
    }

    private static String canonicalizeStationWords(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value
                .replace("Hauptbahnhof", "Hbf")
                .replace("hauptbahnhof", "Hbf")
                .replace("HBF", "Hbf")
                .replace("Bahnhofstr.", "Bahnhofstrasse")
                .replace("Bahnhofstraße", "Bahnhofstrasse")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean isDisplayStopWord(String token) {
        return switch (token.toLowerCase(Locale.ROOT)) {
            case "hbf", "hauptbahnhof", "bahnhof", "bf", "zob", "bus", "str", "strasse",
                    "kaiserstrasse", "bahnhofstrasse", "platz", "kirchenallee",
                    "steintordamm", "nord", "sued", "sud", "ost", "west" -> true;
            default -> false;
        };
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? nullToBlank(second) : first;
    }

    private static String nullToBlank(String value) {
        return value == null ? "" : value;
    }

    private static String explanation(String originalName, String displayName, String displayQuality) {
        return "original=" + nullToBlank(originalName)
                + "; display=" + nullToBlank(displayName)
                + "; quality=" + displayQuality;
    }
}
