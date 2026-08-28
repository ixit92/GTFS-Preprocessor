package de.ixit.gtfs;

import de.ixit.gtfs.model.Agency;
import de.ixit.gtfs.model.Route;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Adds RNV's published line-group colors where a matching GTFS route has none. */
public final class RnvRouteColorEnricher {
    private static final String CATALOG_RESOURCE = "/rnv-route-colors-20260718.csv";

    private RnvRouteColorEnricher() {
    }

    public static Result enrich(List<Route> routes, List<Agency> agencies) throws IOException {
        Map<String, String> agencyNames = new HashMap<>();
        for (Agency agency : agencies) {
            if (agency.agencyId() != null && !agency.agencyId().isBlank()) {
                agencyNames.put(agency.agencyId(), normalizeAgency(agency.agencyName()));
            }
        }
        Map<String, ColorPair> colors = readCatalog();
        ArrayList<Route> enriched = new ArrayList<>(routes.size());
        int appliedCount = 0;
        for (Route route : routes) {
            ColorPair color = colors.get(normalizeLine(route.routeShortName()));
            if (isBlank(route.routeColor())
                    && color != null
                    && belongsToRnv(agencyNames.getOrDefault(route.agencyId(), ""))) {
                enriched.add(new Route(
                        route.routeId(),
                        route.agencyId(),
                        route.routeShortName(),
                        route.routeLongName(),
                        route.routeType(),
                        color.background(),
                        color.text()
                ));
                appliedCount++;
            } else {
                enriched.add(route);
            }
        }
        return new Result(List.copyOf(enriched), appliedCount);
    }

    private static boolean belongsToRnv(String agencyName) {
        return agencyName.contains("rhein neckar verkehr")
                || agencyName.equals("rnv")
                || agencyName.startsWith("rnv ");
    }

    private static Map<String, ColorPair> readCatalog() throws IOException {
        InputStream input = RnvRouteColorEnricher.class.getResourceAsStream(CATALOG_RESOURCE);
        if (input == null) {
            throw new IOException("Missing RNV route color catalog " + CATALOG_RESOURCE);
        }
        HashMap<String, ColorPair> colors = new HashMap<>();
        try (input) {
            GtfsCsvReader.read(input, row -> colors.put(
                    normalizeLine(row.required("line_label")),
                    new ColorPair(
                            normalizeColor(row.required("route_color")),
                            normalizeColor(row.required("route_text_color"))
                    )
            ));
        }
        return Map.copyOf(colors);
    }

    private static String normalizeLine(String value) {
        return value == null ? "" : value.trim().replace(" ", "").toUpperCase(Locale.ROOT);
    }

    private static String normalizeAgency(String value) {
        return value == null
                ? ""
                : value.trim()
                        .toLowerCase(Locale.ROOT)
                        .replaceAll("[^\\p{L}\\p{N}]+", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
    }

    private static String normalizeColor(String value) {
        return value.trim().replace("#", "").toUpperCase(Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ColorPair(String background, String text) {
    }

    public record Result(List<Route> routes, int appliedCount) {
    }
}
