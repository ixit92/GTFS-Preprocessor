package de.ixit.gtfs;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class DisplayAllocationRegressionTest {
    @Test void compiledPatternsKeepLegacyNormalizationExactly() {
        String alphabet = "aAZz  \t\r\n\u00e4\u00f6\u00fc\u00df\u00c4\u00d6\u00dc\u1e9e\u0308\u00a0\u202f\uff0f\uff21.,-/_!&+()[]{}:;0123";
        Random random = new Random(19);
        assertEquals("", StopNameNormalizer.normalize(null));
        for (int sample = 0; sample < 4000; sample++) {
            StringBuilder value = new StringBuilder();
            for (int i = random.nextInt(100); i > 0; i--) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
            String input = value.toString();
            String legacy = Normalizer.normalize(input.trim(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                    .replace("\u00e4", "ae").replace("\u00f6", "oe").replace("\u00fc", "ue").replace("\u00df", "ss")
                    .replace("&", " und ").replace("+", " und ").replaceAll("[\\p{Punct}&&[^/]]+", " ")
                    .replace("/", " ").replaceAll("\\s+", " ").trim();
            assertEquals(legacy, StopNameNormalizer.normalize(input), input);
        }
    }

    @Test void progressDoesNotChangeDisplayFindingsOrNames() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite::memory:"); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE stop_areas(area_id TEXT,area_name TEXT)");
            sql.execute("CREATE TABLE stop_area_cities(area_id TEXT,city_name TEXT)");
            sql.execute("CREATE TABLE stop_area_display_names(area_id TEXT,public_stop_name TEXT,public_city_name TEXT,public_display_name TEXT)");
            sql.execute("INSERT INTO stop_areas VALUES ('a','UNIL-Sorge'),('b','Musterstadt (Ort)'),('c','DO-Hbf')");
            sql.execute("INSERT INTO stop_area_cities VALUES ('a','Lausanne'),('b','Musterstadt'),('c','Dortmund')");
            sql.execute("INSERT INTO stop_area_display_names SELECT area_id,area_name,city_name,area_name||', '||city_name FROM stop_areas JOIN stop_area_cities USING(area_id)");
            sql.execute("UPDATE stop_area_display_names SET public_stop_name='Musterstadt' WHERE area_id='b'");
            var result = DisplayNameQualityBaselineBuilder.build(db);
            var progress = new ArrayList<Long>();
            assertEquals(result, DisplayNameQualityBaselineBuilder.build(db, progress::add));
            assertEquals(6L, progress.getLast());
            assertEquals(2, result.findings().size());
            assertEquals("INSTITUTION_NAME", result.findings().getFirst().classification());
            assertEquals("LOCALITY_STOP_WITH_SOURCE_QUALIFIER", result.findings().getLast().classification());
            assertTrue(result.findings().stream().allMatch(f -> "PRESERVE".equals(f.action())));
        }
    }
}
