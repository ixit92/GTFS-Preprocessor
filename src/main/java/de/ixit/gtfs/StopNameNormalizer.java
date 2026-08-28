package de.ixit.gtfs;

import java.text.Normalizer;
import java.util.Locale;

public final class StopNameNormalizer {
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
                .replace("+", " und ")
                .replaceAll("[\\p{Punct}&&[^/]]+", " ")
                .replace("/", " ")
                .replaceAll("\\s+", " ")
                .trim();

        return normalized;
    }
}
