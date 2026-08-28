package de.ixit.gtfs;

import de.ixit.gtfs.model.Stop;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopSearchToken;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StopSearchTokenBuilder {
    private StopSearchTokenBuilder() {
    }

    public static StopSearchTokenBuildResult build(List<Stop> stops, List<StopArea> stopAreas) {
        TokenAccumulator accumulator = new TokenAccumulator();

        for (Stop stop : stops) {
            String areaId = StopAreaBuilder.areaIdFor(stop);
            addNameTokens(accumulator, stop.stopId(), areaId, stop.stopName(), "STOP_NAME", "NAME");
        }

        for (StopArea area : stopAreas) {
            addNameTokens(accumulator, null, area.areaId(), area.areaName(), "AREA_NAME", "AREA_NAME");
        }

        List<StopSearchToken> tokens = List.copyOf(accumulator.tokens);
        int duplicateTokenCount = accumulator.duplicateTokenCount;
        List<String> emptyTokenSources = List.copyOf(accumulator.emptyTokenSources);
        accumulator.tokens.clear();
        accumulator.seen.clear();
        accumulator.emptyTokenSources.clear();
        System.gc();
        return new StopSearchTokenBuildResult(tokens, duplicateTokenCount, emptyTokenSources);
    }

    private static void addNameTokens(TokenAccumulator accumulator, String stopId, String areaId, String name, String source, String nameTokenType) {
        String normalizedName = StopNameNormalizer.normalize(name);
        if (normalizedName.isBlank()) {
            accumulator.emptyTokenSources.add(source + ":" + (stopId == null ? areaId : stopId));
            return;
        }

        accumulator.add(new StopSearchToken(stopId, areaId, normalizedName, "NORMALIZED", source));

        String[] parts = normalizedName.split(" ");
        for (String part : parts) {
            accumulator.add(new StopSearchToken(stopId, areaId, part, nameTokenType, source));
        }

        for (String synonym : synonyms(parts)) {
            accumulator.add(new StopSearchToken(stopId, areaId, synonym, "SYNONYM", source));
        }
    }

    private static Set<String> synonyms(String[] parts) {
        Set<String> synonyms = new LinkedHashSet<>();
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            switch (part) {
                case "hbf" -> synonyms.add("hauptbahnhof");
                case "bf" -> synonyms.add("bahnhof");
                case "hauptbahnhof" -> synonyms.add("hbf");
                case "bahnhof" -> synonyms.add("bf");
                case "u", "ubahn" -> synonyms.add("ubahn");
                case "s", "sbahn" -> synonyms.add("sbahn");
                default -> {
                }
            }

            if (index + 1 < parts.length && "u".equals(part) && "bahn".equals(parts[index + 1])) {
                synonyms.add("ubahn");
            }
            if (index + 1 < parts.length && "s".equals(part) && "bahn".equals(parts[index + 1])) {
                synonyms.add("sbahn");
            }
        }
        return synonyms;
    }

    private static final class TokenAccumulator {
        private final List<StopSearchToken> tokens = new ArrayList<>();
        private final Set<TokenKey> seen = new LinkedHashSet<>();
        private final List<String> emptyTokenSources = new ArrayList<>();
        private int duplicateTokenCount;

        private void add(StopSearchToken token) {
            if (token.token() == null || token.token().isBlank()) {
                emptyTokenSources.add(token.source() + ":" + (token.stopId() == null ? token.areaId() : token.stopId()));
                return;
            }

            TokenKey key = new TokenKey(token.stopId(), token.areaId(), token.token(), token.tokenType(), token.source());
            if (seen.add(key)) {
                tokens.add(token);
            } else {
                duplicateTokenCount++;
            }
        }
    }

    private record TokenKey(String stopId, String areaId, String token, String tokenType, String source) {
    }

    public record StopSearchTokenBuildResult(
            List<StopSearchToken> tokens,
            int duplicateTokenCount,
            List<String> emptyTokenSources
    ) {
        public Map<String, Long> tokenCountsByType() {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (StopSearchToken token : tokens) {
                counts.merge(token.tokenType(), 1L, Long::sum);
            }
            return counts;
        }
    }
}
