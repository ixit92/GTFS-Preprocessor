package de.ixit.gtfs;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

public final class StopNameNormalizer {
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}&&[^/]]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private StopNameNormalizer() {
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }

        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("ä", "ae")
                .replace("ö", "oe")
                .replace("ü", "ue")
                .replace("ß", "ss");

        normalized = normalized
                .replace("&", " und ")
                .replace("+", " und ");
        normalized = PUNCTUATION.matcher(normalized).replaceAll(" ").replace("/", " ");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }
}
