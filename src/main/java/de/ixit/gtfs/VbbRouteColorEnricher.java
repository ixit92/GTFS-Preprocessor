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
import java.util.Set;

public final class VbbRouteColorEnricher {
    private static final String CATALOG_RESOURCE = "/vbb-route-colors-20260428.csv";
    private static final Set<String> REGIONAL_OPERATORS = Set.of(
            "db regio ag nordost",
            "800151 db regio ag nordost",
            "ostdeutsche eisenbahn gmbh",
            "odeg ostdeutsche eisenbahn gmbh",
            "neb niederbarnimer eisenbahn",
            "neb betriebsgesellschaft mbh",
            "hanseatische eisenbahn",
            "hanseatische eisenbahn gmbh",
            "mitteldeutsche regiobahn"
    );

    private VbbRouteColorEnricher() {
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
            String label = normalizeLine(route.routeShortName());
            ColorPair color = colors.get(label);
            String agencyName = agencyNames.getOrDefault(route.agencyId(), "");
            if (isBlank(route.routeColor()) && color != null && belongsToVbb(label, agencyName)) {
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

    private static boolean belongsToVbb(String label, String agencyName) {
        if (label.matches("U[1-9]")) {
            return agencyName.equals("berliner verkehrsbetriebe");
        }
        if (label.matches("S[0-9]+")) {
            return agencyName.equals("s bahn berlin gmbh");
        }
        if (label.equals("FEX") || label.matches("R[BE][0-9]+") || label.matches("X[489]")) {
            return REGIONAL_OPERATORS.contains(agencyName);
        }
        return false;
    }

    private static Map<String, ColorPair> readCatalog() throws IOException {
        InputStream input = VbbRouteColorEnricher.class.getResourceAsStream(CATALOG_RESOURCE);
        if (input == null) {
            throw new IOException("Missing VBB route color catalog " + CATALOG_RESOURCE);
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
