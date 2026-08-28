package de.ixit.gtfs;

import de.ixit.gtfs.model.CanonicalStopArea;
import de.ixit.gtfs.model.CanonicalStopAreaMember;
import de.ixit.gtfs.model.CanonicalStopAreaName;
import de.ixit.gtfs.model.CanonicalStopAreaTransferEdge;
import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaAlias;
import de.ixit.gtfs.model.StopAreaCity;
import de.ixit.gtfs.model.StopAreaProfile;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CanonicalStopAreaBuilder {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double GRID_DEGREES = 0.005;
    private static final int NEARBY_MEMBER_MAX_METERS = 320;
    private static final int PRIMARY_FAMILY_MERGE_MAX_METERS = 450;
    private static final int GENERIC_RAIL_FAMILY_MERGE_MAX_METERS = 2_500;
    private static final int NAMED_RAIL_FAMILY_MERGE_MAX_METERS = 5_000;
    private static final int PRIMARY_FAMILY_MERGE_GRID_RADIUS = 8;
    private static final int FAMILY_RELEASE_ROWS = 25_000;

    private final Map<String, AreaInfo> areasById = new LinkedHashMap<>();
    private final Map<String, StopAreaProfile> profilesByAreaId = new LinkedHashMap<>();
    private final Map<String, List<StopAreaAlias>> aliasesByAreaId = new LinkedHashMap<>();
    private final Map<String, String> citiesByAreaId = new LinkedHashMap<>();

    public CanonicalStopAreaBuilder(List<StopArea> stopAreas, List<StopAreaProfile> profiles) {
        this(stopAreas, profiles, List.of(), List.of());
    }

    public CanonicalStopAreaBuilder(
            List<StopArea> stopAreas,
            List<StopAreaProfile> profiles,
            List<StopAreaAlias> aliases
    ) {
        this(stopAreas, profiles, aliases, List.of());
    }

    public CanonicalStopAreaBuilder(
            List<StopArea> stopAreas,
            List<StopAreaProfile> profiles,
            List<StopAreaAlias> aliases,
            List<StopAreaCity> cities
    ) {
        for (StopAreaProfile profile : profiles) {
            profilesByAreaId.put(profile.areaId(), profile);
        }
        for (StopAreaAlias alias : aliases) {
            aliasesByAreaId.computeIfAbsent(alias.areaId(), ignored -> new ArrayList<>()).add(alias);
        }
        for (StopAreaCity city : cities) {
            if (city.areaId() != null && !city.areaId().isBlank()
                    && city.cityName() != null && !city.cityName().isBlank()) {
                citiesByAreaId.putIfAbsent(city.areaId(), city.cityName());
            }
        }
        for (StopArea area : stopAreas) {
            StopAreaProfile profile = profilesByAreaId.get(area.areaId());
            areasById.put(area.areaId(), new AreaInfo(area, profile));
        }
    }

    public CanonicalStopAreaBuildResult build() {
        List<AreaInfo> primaries = areasById.values().stream()
                .filter(AreaInfo::isCanonicalPrimaryCandidate)
                .sorted(Comparator
                        .comparing((AreaInfo area) -> !area.profile().hasTrain())
                        .thenComparing(area -> !area.profile().hasRailService())
                        .thenComparing(area -> !area.profile().mainStationSignal())
                        .thenComparing((AreaInfo area) -> -area.profile().searchPriorityScore())
                        .thenComparing(area -> area.area().areaName())
                        .thenComparing(area -> area.area().areaId()))
                .toList();

        Map<String, List<AreaInfo>> grid = buildGrid(areasById.values().stream()
                .filter(area -> area.area().areaLat() != null && area.area().areaLon() != null)
                .toList());
        Map<String, FamilyAccumulator> familiesByPrimary = new LinkedHashMap<>();
        Map<String, List<FamilyAccumulator>> familyGrid = new HashMap<>();
        Map<String, FamilyAccumulator> familyByMemberAreaId = new HashMap<>();
        Map<String, FamilyAccumulator> familyByGenericStationKey = new HashMap<>();
        Map<String, MemberAssignment> assignedNonPrimaryMembers = new HashMap<>();

        for (AreaInfo primary : primaries) {
            FamilyAccumulator existingFamily = findMergeFamily(familyGrid, primary);
            if (existingFamily == null) {
                existingFamily = findNamedMergeFamily(familyByGenericStationKey, primary);
            }
            if (existingFamily == null) {
                String canonicalAreaId = canonicalAreaId(primary.area().areaId());
                FamilyAccumulator family = new FamilyAccumulator(canonicalAreaId, primary);
                String role = primaryRole(primary);
                family.add(primary, role, "GOOD", 0, "PRIMARY_PROFILE", "primary active service area");
                familiesByPrimary.put(primary.area().areaId(), family);
                familyByMemberAreaId.put(primary.area().areaId(), family);
                addFamilyToGrid(familyGrid, family);
                addFamilyToGenericStationIndex(familyByGenericStationKey, family);
            } else {
                int distanceMeters = distanceMetersOrNull(existingFamily.primary(), primary) == null
                        ? NAMED_RAIL_FAMILY_MERGE_MAX_METERS
                        : distanceMetersOrNull(existingFamily.primary(), primary);
                String role = secondaryPrimaryRole(primary);
                String quality = qualityForDistance(distanceMeters);
                existingFamily.add(
                        primary,
                        role,
                        quality,
                        distanceMeters,
                        "PRIMARY_FAMILY_MERGE",
                        "nearby station-family primary merged into "
                                + existingFamily.primary().area().areaId()
                                + " within " + distanceMeters + "m"
                );
                familyByMemberAreaId.put(primary.area().areaId(), existingFamily);
            }
        }

        for (FamilyAccumulator family : familiesByPrimary.values()) {
            for (Member anchor : family.members()) {
                AreaInfo anchorArea = anchor.area();
                if (anchorArea.area().areaLat() == null || anchorArea.area().areaLon() == null) {
                    continue;
                }
                for (AreaInfo nearby : nearbyAreas(grid, anchorArea)) {
                    if (family.contains(nearby.area().areaId())) {
                        continue;
                    }
                    if (familyByMemberAreaId.containsKey(nearby.area().areaId())) {
                        continue;
                    }
                if (nearby.isCanonicalPrimaryCandidate()) {
                    continue;
                }
                    int distanceMeters = (int) Math.round(distanceMeters(family.primary().area(), nearby.area()));
                if (distanceMeters > NEARBY_MEMBER_MAX_METERS) {
                    continue;
                }
                    if (!sameCityOrStationFamily(family.primary(), nearby)
                            && !sameCityOrStationFamily(anchorArea, nearby)) {
                    continue;
                }
                String role = memberRole(nearby);
                if ("ALIAS_ONLY".equals(role)) {
                    continue;
                }
                if ("BUS_FEEDER".equals(role) && !hasStationFamilySignal(nearby)) {
                    continue;
                }
                String quality = distanceMeters <= 150 ? "GOOD" : distanceMeters <= 250 ? "OK" : "LONG";
                MemberAssignment previous = assignedNonPrimaryMembers.get(nearby.area().areaId());
                if (previous != null) {
                    continue;
                }
                assignedNonPrimaryMembers.put(
                        nearby.area().areaId(),
                            new MemberAssignment(family.primary().area().areaId(), distanceMeters)
                );
                family.add(
                        nearby,
                        role,
                        quality,
                        distanceMeters,
                        "NEARBY_PROFILE_DISTANCE",
                        "nearby " + nearby.profile().profileClass() + " area within " + distanceMeters + "m"
                );
                familyByMemberAreaId.put(nearby.area().areaId(), family);
                }
            }
        }

        areasById.values().stream()
                .filter(AreaInfo::hasActiveService)
                .filter(area -> !familyByMemberAreaId.containsKey(area.area().areaId()))
                .sorted(Comparator.comparing(area -> area.area().areaId()))
                .forEach(area -> {
                    String canonicalAreaId = canonicalAreaId(area.area().areaId());
                    FamilyAccumulator family = new FamilyAccumulator(canonicalAreaId, area);
                    family.add(
                            area,
                            primaryRole(area),
                            "GOOD",
                            0,
                            "STANDALONE_ACTIVE_AREA",
                            "active stop area not assigned to a station family"
                    );
                    familiesByPrimary.put(area.area().areaId(), family);
                    familyByMemberAreaId.put(area.area().areaId(), family);
                });

        grid.clear();
        familyGrid.clear();
        familyByMemberAreaId.clear();
        familyByGenericStationKey.clear();
        assignedNonPrimaryMembers.clear();
        System.gc();

        List<CanonicalStopArea> canonicalAreas = new ArrayList<>();
        List<CanonicalStopAreaMember> members = new ArrayList<>();
        List<CanonicalStopAreaTransferEdge> transferEdges = new ArrayList<>();
        var familyIterator = familiesByPrimary.values().iterator();
        int convertedFamilies = 0;
        while (familyIterator.hasNext()) {
            FamilyAccumulator family = familyIterator.next();
            canonicalAreas.add(family.toCanonicalStopArea());
            members.addAll(family.toMembers());
            transferEdges.addAll(family.toTransferEdges());
            familyIterator.remove();
            convertedFamilies++;
            if (convertedFamilies % FAMILY_RELEASE_ROWS == 0) {
                System.gc();
            }
        }
        areasById.clear();
        profilesByAreaId.clear();
        aliasesByAreaId.clear();
        citiesByAreaId.clear();
        System.gc();
        canonicalAreas.sort(Comparator.comparing(CanonicalStopArea::canonicalAreaId));
        members.sort(Comparator
                .comparing(CanonicalStopAreaMember::canonicalAreaId)
                .thenComparing(CanonicalStopAreaMember::memberRole)
                .thenComparing(CanonicalStopAreaMember::areaId));
        transferEdges.sort(Comparator
                .comparing(CanonicalStopAreaTransferEdge::canonicalAreaId)
                .thenComparing(CanonicalStopAreaTransferEdge::fromAreaId)
                .thenComparing(CanonicalStopAreaTransferEdge::toAreaId));

        CanonicalStopAreaStats stats = CanonicalStopAreaStats.from(canonicalAreas, members);
        return new CanonicalStopAreaBuildResult(
                List.copyOf(canonicalAreas),
                List.copyOf(members),
                List.copyOf(transferEdges),
                stats
        );
    }

    private static Map<String, List<AreaInfo>> buildGrid(List<AreaInfo> areas) {
        Map<String, List<AreaInfo>> grid = new HashMap<>();
        for (AreaInfo area : areas) {
            grid.computeIfAbsent(gridKey(area.area()), ignored -> new ArrayList<>()).add(area);
        }
        return grid;
    }

    private static List<AreaInfo> nearbyAreas(Map<String, List<AreaInfo>> grid, AreaInfo center) {
        int latCell = gridCell(center.area().areaLat());
        int lonCell = gridCell(center.area().areaLon());
        List<AreaInfo> areas = new ArrayList<>();
        for (int latOffset = -1; latOffset <= 1; latOffset++) {
            for (int lonOffset = -1; lonOffset <= 1; lonOffset++) {
                List<AreaInfo> cell = grid.get((latCell + latOffset) + ":" + (lonCell + lonOffset));
                if (cell != null) {
                    areas.addAll(cell);
                }
            }
        }
        return areas;
    }

    private static String gridKey(StopArea area) {
        return gridCell(area.areaLat()) + ":" + gridCell(area.areaLon());
    }

    private static int gridCell(Double value) {
        return (int) Math.floor(value / GRID_DEGREES);
    }

    private static boolean sameCityOrStationFamily(AreaInfo primary, AreaInfo candidate) {
        Set<String> primaryTokens = meaningfulNameTokens(primary.area().areaName());
        Set<String> candidateTokens = meaningfulNameTokens(candidate.area().areaName());
        for (String token : primaryTokens) {
            if (candidateTokens.contains(token)) {
                return true;
            }
        }
        return primary.profile().mainStationSignal() && candidate.profile().mainStationSignal();
    }

    private static Set<String> meaningfulNameTokens(String name) {
        String normalized = stripQualifiers(StopNameNormalizer.normalize(name));
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalized.split(" ")) {
            if (token.length() < 3 || isDisplayStopWord(token)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private static boolean isDisplayStopWord(String token) {
        return switch (token) {
            case "hbf", "hauptbahnhof", "bahnhof", "zob", "bus", "str", "strasse",
                    "kaiserstrasse", "bahnhofstrasse", "platz", "kirchenallee",
                    "steintordamm", "nord", "sued", "sud", "ost", "west" -> true;
            default -> false;
        };
    }

    private static String memberRole(AreaInfo area) {
        StopAreaProfile profile = area.profile();
        if (profile.busOnly()) {
            return "BUS_FEEDER";
        }
        if (profile.hasSubway() || profile.hasTram()) {
            return "NEARBY_URBAN";
        }
        return "ALIAS_ONLY";
    }

    private static String secondaryPrimaryRole(AreaInfo area) {
        StopAreaProfile profile = area.profile();
        if (profile.hasTrain() || profile.hasRailService()) {
            return "RAIL_PLATFORM";
        }
        if (profile.hasSubway() || profile.hasTram()) {
            return "NEARBY_URBAN";
        }
        if (profile.busOnly()) {
            return "BUS_FEEDER";
        }
        return "ALIAS_ONLY";
    }

    private static String primaryRole(AreaInfo area) {
        StopAreaProfile profile = area.profile();
        if (profile.hasRailService() || profile.hasSubway() || profile.hasTram()) {
            return "PRIMARY_RAIL";
        }
        if (profile.busOnly()) {
            return "PRIMARY_BUS";
        }
        return "PRIMARY_STOP";
    }

    private static boolean isPrimaryRole(String role) {
        return role != null && role.startsWith("PRIMARY_");
    }

    private static String displayRole(Member member) {
        StopAreaProfile profile = member.area().profile();
        if (isPrimaryRole(member.role())) {
            if (profile.hasTrain()) {
                return "Fernbahn / Regionalbahn";
            }
            if (profile.hasSubway()) {
                return "U-Bahn";
            }
            if (profile.hasTram()) {
                return "Tram / Stadtbahn";
            }
            if (profile.busOnly()) {
                return "Bus";
            }
            return "Primaerer Routinganker";
        }
        if ("BUS_FEEDER".equals(member.role())) {
            return "Bus / ZOB / Vorplatz";
        }
        if ("RAIL_PLATFORM".equals(member.role())) {
            if (profile.hasTrain()) {
                return "Weitere Fernbahn / Regionalbahn";
            }
            return "Weiterer Rail-Anker";
        }
        if ("NEARBY_URBAN".equals(member.role())) {
            return "S-/U-/Tram-Umstieg";
        }
        return "Technisches Mitglied";
    }

    private static boolean primaryForSearch(Member member) {
        return isPrimaryRole(member.role());
    }

    private static boolean primaryForRouting(Member member) {
        return isPrimaryRole(member.role())
                || "RAIL_PLATFORM".equals(member.role())
                || "NEARBY_URBAN".equals(member.role());
    }

    private static boolean visibleSuggestion(Member member) {
        return primaryForSearch(member);
    }

    private static int accessCostMinutes(Member member) {
        if (isPrimaryRole(member.role())) {
            return 0;
        }
        return minutesForDistance(member.distanceMeters());
    }

    private static int minutesForDistance(Integer distanceMeters) {
        if (distanceMeters == null || distanceMeters <= 0) {
            return 2;
        }
        if (distanceMeters <= 80) {
            return 2;
        }
        if (distanceMeters <= 200) {
            return 4;
        }
        if (distanceMeters <= 400) {
            return 6;
        }
        return 10;
    }

    private static String qualityForDistance(Integer distanceMeters) {
        if (distanceMeters == null || distanceMeters <= 200) {
            return "GOOD";
        }
        if (distanceMeters <= 400) {
            return "OK";
        }
        return "LONG";
    }

    private String displayName(FamilyAccumulator family) {
        AreaInfo primary = family.primary();
        AreaInfo bestDisplayMember = family.members().stream()
                .filter(member -> member.area().profile().stationNameSignal())
                .max(Comparator
                        .comparingInt((Member member) -> displayMemberScore(primary, member))
                        .thenComparing(member -> -cleanDisplayName(member.area().area().areaName()).length()))
                .map(Member::area)
                .orElseGet(() -> family.members().stream()
                        .max(Comparator
                                .comparingInt((Member member) -> displayMemberScore(primary, member))
                                .thenComparing(member -> -cleanDisplayName(member.area().area().areaName()).length()))
                        .map(Member::area)
                        .orElse(primary));

        String candidate = cleanDisplayName(bestDisplayMember.area().areaName());
        String aliasDisplayName = isRailCityOnlyDisplayName(primary, candidate) && primary.profile().hasTrain()
                ? family.bestGenericRailCityDisplayName(officialCityName(primary))
                : family.bestAliasDisplayName();
        if (aliasDisplayName.isBlank() && isRailCityOnlyDisplayName(primary, candidate)) {
            aliasDisplayName = family.bestSpecificPlaceDisplayName(officialCityName(primary));
        }
        if (!aliasDisplayName.isBlank()
                && (isBareStationDisplayName(candidate)
                || isLevelQualifiedStationName(bestDisplayMember.area().areaName())
                || !hasUsableCity(candidate)
                || isRailCityOnlyDisplayName(primary, candidate))) {
            candidate = aliasDisplayName;
        }
        if (isTechnicalDisplayName(candidate) && !primary.area().areaName().equals(bestDisplayMember.area().areaName())) {
            candidate = cleanDisplayName(primary.area().areaName());
        }
        if (!aliasDisplayName.isBlank()
                && (isBareStationDisplayName(candidate)
                || isLevelQualifiedStationName(primary.area().areaName())
                || !hasUsableCity(candidate))) {
            candidate = aliasDisplayName;
        }
        if (isTechnicalDisplayName(candidate)) {
            String city = cityName(primary.area().areaName());
            if (!city.isBlank() && (primary.profile().mainStationSignal() || primary.profile().hasRailService())) {
                return city + " Bahnhof";
            }
        }
        return candidate;
    }

    private boolean isRailCityOnlyDisplayName(AreaInfo primary, String displayName) {
        if (!primary.profile().hasRailService()) {
            return false;
        }
        String city = officialCityName(primary);
        return !city.isBlank()
                && StopNameNormalizer.normalize(displayName)
                .equals(StopNameNormalizer.normalize(city));
    }

    private String officialCityName(AreaInfo area) {
        return citiesByAreaId.getOrDefault(area.area().areaId(), "");
    }

    private static int displayMemberScore(AreaInfo primary, Member member) {
        String displayName = cleanDisplayName(member.area().area().areaName());
        String normalized = StopNameNormalizer.normalize(displayName);
        String primaryCity = StopNameNormalizer.normalize(cityName(primary.area().areaName()));
        int score = 0;
        if (member.area().profile().stationNameSignal()) {
            score += 120;
        }
        if (member.area().profile().mainStationSignal()) {
            score += 80;
        }
        if (!isTechnicalDisplayName(displayName)) {
            score += 70;
        }
        if (!primaryCity.isBlank()
                && (normalized.equals(primaryCity + " bahnhof")
                || normalized.equals(primaryCity + " hbf")
                || normalized.equals(primaryCity + " hauptbahnhof"))) {
            score += 160;
        }
        if (normalized.contains("bahnhofstr")
                || normalized.contains("kaiserstr")
                || normalized.contains("kirchenallee")
                || normalized.contains("steintordamm")) {
            score -= 140;
        }
        if (normalized.contains("zob") || normalized.contains("bus")) {
            score -= 120;
        }
        if (member.area().profile().busOnly()) {
            score -= 20;
        }
        return score;
    }

    private static String cleanDisplayName(String name) {
        return StopAreaNameHarmonizer.cleanDisplayName(name);
    }

    private static boolean isTechnicalDisplayName(String displayName) {
        return StopAreaNameHarmonizer.isTechnicalDisplayName(displayName);
    }

    private static boolean hasUsableCity(String displayName) {
        return !cityName(displayName).isBlank();
    }

    private static boolean isBareStationDisplayName(String displayName) {
        String normalized = StopNameNormalizer.normalize(displayName);
        return "hbf".equals(normalized)
                || "hauptbahnhof".equals(normalized)
                || "bahnhof".equals(normalized)
                || "bf".equals(normalized);
    }

    private static boolean isLevelQualifiedStationName(String displayName) {
        String normalized = StopNameNormalizer.normalize(displayName);
        return (normalized.contains("hauptbahnhof") || normalized.contains("hbf") || normalized.contains("bahnhof"))
                && (containsToken(normalized, "oben")
                || containsToken(normalized, "tief")
                || containsToken(normalized, "upper")
                || containsToken(normalized, "lower"));
    }

    private static String cityName(String name) {
        return StopAreaNameHarmonizer.cityName(name);
    }

    private static String stationName(String displayName, String cityName) {
        return StopAreaNameHarmonizer.stationName(displayName, cityName);
    }

    private static String displayQuality(String displayName) {
        return StopAreaNameHarmonizer.displayQuality(displayName);
    }

    private static boolean containsBusOrStreetSignal(String normalizedName) {
        return normalizedName.contains("zob")
                || normalizedName.contains("bus")
                || normalizedName.contains("bahnhofstr")
                || normalizedName.contains("kaiserstr")
                || normalizedName.contains("kirchenallee")
                || normalizedName.contains("steintordamm");
    }

    private static String stripQualifiers(String normalizedName) {
        return normalizedName
                .replaceAll("\\b(krs|kreis|landkreis|kr)\\b.*", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String canonicalAreaId(String primaryAreaId) {
        return "CAN_" + primaryAreaId;
    }

    private static void addFamilyToGrid(
            Map<String, List<FamilyAccumulator>> familyGrid,
            FamilyAccumulator family
    ) {
        if (family.primary().area().areaLat() == null || family.primary().area().areaLon() == null) {
            return;
        }
        familyGrid.computeIfAbsent(gridKey(family.primary().area()), ignored -> new ArrayList<>()).add(family);
    }

    private static void addFamilyToGenericStationIndex(
            Map<String, FamilyAccumulator> familiesByGenericStationKey,
            FamilyAccumulator family
    ) {
        String key = genericStationMergeKey(family.primary());
        if (!key.isBlank()) {
            familiesByGenericStationKey.putIfAbsent(key, family);
        }
    }

    private static FamilyAccumulator findNamedMergeFamily(
            Map<String, FamilyAccumulator> familiesByGenericStationKey,
            AreaInfo candidate
    ) {
        String key = genericStationMergeKey(candidate);
        if (key.isBlank()) {
            return null;
        }
        FamilyAccumulator family = familiesByGenericStationKey.get(key);
        if (family == null || !canMergePrimaryIntoFamilyByName(family, candidate)) {
            return null;
        }
        return family;
    }

    private static FamilyAccumulator findMergeFamily(
            Map<String, List<FamilyAccumulator>> familyGrid,
            AreaInfo candidate
    ) {
        if (candidate.area().areaLat() == null || candidate.area().areaLon() == null) {
            return null;
        }
        FamilyAccumulator bestFamily = null;
        int bestDistance = Integer.MAX_VALUE;
        int latCell = gridCell(candidate.area().areaLat());
        int lonCell = gridCell(candidate.area().areaLon());
        for (int latOffset = -PRIMARY_FAMILY_MERGE_GRID_RADIUS; latOffset <= PRIMARY_FAMILY_MERGE_GRID_RADIUS; latOffset++) {
            for (int lonOffset = -PRIMARY_FAMILY_MERGE_GRID_RADIUS; lonOffset <= PRIMARY_FAMILY_MERGE_GRID_RADIUS; lonOffset++) {
                List<FamilyAccumulator> cellFamilies = familyGrid.get((latCell + latOffset) + ":" + (lonCell + lonOffset));
                if (cellFamilies == null) {
                    continue;
                }
                for (FamilyAccumulator family : cellFamilies) {
                    if (!canMergePrimaryIntoFamily(family, candidate)) {
                        continue;
                    }
                    int distanceMeters = (int) Math.round(distanceMeters(family.primary().area(), candidate.area()));
                    if (distanceMeters < bestDistance) {
                        bestDistance = distanceMeters;
                        bestFamily = family;
                    }
                }
            }
        }
        return bestFamily;
    }

    private static boolean canMergePrimaryIntoFamily(FamilyAccumulator family, AreaInfo candidate) {
        AreaInfo primary = family.primary();
        if (primary.area().areaLat() == null
                || primary.area().areaLon() == null
                || candidate.area().areaLat() == null
                || candidate.area().areaLon() == null) {
            return false;
        }
        int distanceMeters = (int) Math.round(distanceMeters(primary.area(), candidate.area()));
        if (!sameCityOrStationFamily(primary, candidate)) {
            return false;
        }
        if (!hasStationFamilySignal(primary) || !hasStationFamilySignal(candidate)) {
            return false;
        }
        if (distanceMeters <= PRIMARY_FAMILY_MERGE_MAX_METERS) {
            return true;
        }
        if (distanceMeters > GENERIC_RAIL_FAMILY_MERGE_MAX_METERS) {
            return false;
        }
        if (!hasStrongerRailPrimary(primary, candidate)) {
            return false;
        }
        return isGenericCityStationPair(primary, candidate) || isTechnicalStationMember(primary, candidate);
    }

    private static boolean canMergePrimaryIntoFamilyByName(FamilyAccumulator family, AreaInfo candidate) {
        AreaInfo primary = family.primary();
        if (!sameCityOrStationFamily(primary, candidate)) {
            return false;
        }
        if (!hasStationFamilySignal(primary) || !hasStationFamilySignal(candidate)) {
            return false;
        }
        if (!hasStrongerRailPrimary(primary, candidate)) {
            return false;
        }
        if (!isGenericCityStationPair(primary, candidate) && !isTechnicalStationMember(primary, candidate)) {
            return false;
        }
        Integer distanceMeters = distanceMetersOrNull(primary, candidate);
        return distanceMeters == null || distanceMeters <= NAMED_RAIL_FAMILY_MERGE_MAX_METERS;
    }

    private static boolean hasStationFamilySignal(AreaInfo area) {
        StopAreaProfile profile = area.profile();
        String normalized = area.normalizedName();
        return profile.stationNameSignal()
                || profile.mainStationSignal()
                || (profile.hasRailService() && isGenericCityStationName(area))
                || normalized.contains("hbf")
                || normalized.contains("hauptbahnhof")
                || normalized.contains("bahnhof")
                || normalized.contains("zob");
    }

    private static boolean hasStrongerRailPrimary(AreaInfo primary, AreaInfo candidate) {
        StopAreaProfile primaryProfile = primary.profile();
        StopAreaProfile candidateProfile = candidate.profile();
        if (primaryProfile == null || candidateProfile == null) {
            return false;
        }
        if (!primaryProfile.hasRailService() || !candidateProfile.hasRailService()) {
            return false;
        }
        if (primaryProfile.hasTrain() && !candidateProfile.hasTrain()) {
            return true;
        }
        return primaryProfile.searchPriorityScore() >= candidateProfile.searchPriorityScore();
    }

    private static boolean isGenericCityStationPair(AreaInfo primary, AreaInfo candidate) {
        if (!isGenericCityStationName(primary) || !isGenericCityStationName(candidate)) {
            return false;
        }
        String primaryKey = stationFamilyBaseKey(cityName(primary.area().areaName()));
        String candidateKey = stationFamilyBaseKey(cityName(candidate.area().areaName()));
        return !primaryKey.isBlank() && primaryKey.equals(candidateKey);
    }

    private static boolean isGenericCityStationName(AreaInfo area) {
        String city = StopNameNormalizer.normalize(cityName(area.area().areaName()));
        if (city.isBlank()) {
            return false;
        }
        String normalized = stripQualifiers(area.normalizedName());
        return normalized.equals(city)
                || normalized.equals(city + " bahnhof")
                || normalized.equals(city + " bf")
                || normalized.equals(city + " hbf")
                || normalized.equals(city + " hauptbahnhof");
    }

    private static boolean isTechnicalStationMember(AreaInfo primary, AreaInfo candidate) {
        return (isTechnicalRailStationName(primary) && isGenericCityStationName(candidate))
                || (isTechnicalRailStationName(candidate) && isGenericCityStationName(primary));
    }

    private static boolean isTechnicalRailStationName(AreaInfo area) {
        StopAreaProfile profile = area.profile();
        if (profile == null || !profile.hasRailService()) {
            return false;
        }
        String normalized = area.normalizedName();
        return normalized.contains("kaiserstr")
                || normalized.contains("bahnhofstr")
                || normalized.contains("kirchenallee")
                || normalized.contains("steintordamm")
                || containsToken(normalized, "str")
                || containsToken(normalized, "strasse");
    }

    private static boolean containsToken(String normalized, String token) {
        return (" " + normalized + " ").contains(" " + token + " ");
    }

    private static String genericStationMergeKey(AreaInfo area) {
        if (!isGenericCityStationName(area) && !isTechnicalRailStationName(area)) {
            return "";
        }
        String city = stationFamilyBaseKey(cityName(area.area().areaName()));
        if (!city.isBlank()) {
            return city;
        }
        return stationFamilyBaseKey(area.area().areaName());
    }

    private static String stationFamilyBaseKey(String value) {
        String normalized = stripQualifiers(StopNameNormalizer.normalize(value));
        normalized = normalized
                .replaceAll("\\bbahnhof\\b.*", "")
                .replaceAll("\\bbf\\b.*", "")
                .replaceAll("\\bhauptbahnhof\\b.*", "")
                .replaceAll("\\bhbf\\b.*", "")
                .replaceAll("\\s+", " ")
                .trim();
        return normalized;
    }

    private static Integer distanceMetersOrNull(AreaInfo a, AreaInfo b) {
        if (a.area().areaLat() == null
                || a.area().areaLon() == null
                || b.area().areaLat() == null
                || b.area().areaLon() == null) {
            return null;
        }
        return (int) Math.round(distanceMeters(a.area(), b.area()));
    }

    private static double distanceMeters(StopArea a, StopArea b) {
        double lat1 = Math.toRadians(a.areaLat());
        double lat2 = Math.toRadians(b.areaLat());
        double dLat = Math.toRadians(b.areaLat() - a.areaLat());
        double dLon = Math.toRadians(b.areaLon() - a.areaLon());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }

    private record AreaInfo(StopArea area, StopAreaProfile profile) {
        boolean isCanonicalPrimaryCandidate() {
            return profile != null && (profile.hasRailService() || profile.hasSubway() || profile.hasTram());
        }

        boolean hasActiveService() {
            return profile != null && profile.routeCount() > 0;
        }

        String normalizedName() {
            return StopNameNormalizer.normalize(area.areaName());
        }
    }

    private record Member(AreaInfo area, String role, String quality, Integer distanceMeters, String source, String explanation) {
    }

    private record MemberAssignment(String primaryAreaId, int distanceMeters) {
    }

    private final class FamilyAccumulator {
        private final String canonicalAreaId;
        private final AreaInfo primary;
        private final Map<String, Member> members = new LinkedHashMap<>();

        private FamilyAccumulator(String canonicalAreaId, AreaInfo primary) {
            this.canonicalAreaId = canonicalAreaId;
            this.primary = primary;
        }

        private AreaInfo primary() {
            return primary;
        }

        private List<Member> members() {
            return new ArrayList<>(members.values());
        }

        private boolean contains(String areaId) {
            return members.containsKey(areaId);
        }

        private void add(
                AreaInfo area,
                String role,
                String quality,
                Integer distanceMeters,
                String source,
                String explanation
        ) {
            members.put(area.area().areaId(), new Member(area, role, quality, distanceMeters, source, explanation));
        }

        private String bestAliasDisplayName() {
            return members.values().stream()
                    .flatMap(member -> aliasesByAreaId
                            .getOrDefault(member.area().area().areaId(), List.of())
                            .stream())
                    .map(CanonicalStopAreaBuilder::aliasDisplayCandidate)
                    .filter(candidate -> !candidate.value().isBlank())
                    .max(Comparator
                            .comparingInt(AliasDisplayCandidate::score)
                            .thenComparing(AliasDisplayCandidate::value))
                    .map(AliasDisplayCandidate::value)
                    .orElse("");
        }

        private String bestSpecificPlaceDisplayName(String city) {
            return members.values().stream()
                    .flatMap(member -> aliasesByAreaId
                            .getOrDefault(member.area().area().areaId(), List.of())
                            .stream())
                    .map(alias -> specificPlaceAliasDisplayCandidate(alias, city))
                    .filter(candidate -> !candidate.value().isBlank())
                    .max(Comparator
                            .comparingInt(AliasDisplayCandidate::score)
                            .thenComparing(candidate -> -candidate.value().length())
                            .thenComparing(AliasDisplayCandidate::value))
                    .map(AliasDisplayCandidate::value)
                    .orElse("");
        }

        private String bestGenericRailCityDisplayName(String city) {
            String normalizedCity = StopNameNormalizer.normalize(city);
            if (normalizedCity.isBlank()) {
                return "";
            }
            return members.values().stream()
                    .flatMap(member -> aliasesByAreaId
                            .getOrDefault(member.area().area().areaId(), List.of())
                            .stream())
                    .map(alias -> genericRailCityAliasDisplayCandidate(alias, city, normalizedCity))
                    .filter(candidate -> !candidate.value().isBlank())
                    .max(Comparator
                            .comparingInt(AliasDisplayCandidate::score)
                            .thenComparing(AliasDisplayCandidate::value))
                    .map(AliasDisplayCandidate::value)
                    .orElse("");
        }

        private CanonicalStopArea toCanonicalStopArea() {
            CanonicalStopAreaName harmonizedName = StopAreaNameHarmonizer.harmonize(
                    canonicalAreaId,
                    primary.area().areaName(),
                    primary.profile(),
                    displayName(this),
                    "CANONICAL_FAMILY_NAME",
                    officialCityName(primary)
            );
            return new CanonicalStopArea(
                    canonicalAreaId,
                    harmonizedName.displayName(),
                    harmonizedName.originalName(),
                    harmonizedName.cityName(),
                    harmonizedName.stationName(),
                    harmonizedName.nameOrder(),
                    primary.area().areaId(),
                    primary.profile().profileClass(),
                    primary.profile().hasRailService(),
                    primary.profile().lineLabels(),
                    members.size(),
                    harmonizedName.displayQuality(),
                    harmonizedName.source(),
                    harmonizedName.explanation() + "; members=" + members.size()
            );
        }

        private List<CanonicalStopAreaMember> toMembers() {
            List<CanonicalStopAreaMember> result = new ArrayList<>();
            for (Member member : members.values()) {
                result.add(new CanonicalStopAreaMember(
                        canonicalAreaId,
                        member.area().area().areaId(),
                        member.role(),
                        displayRole(member),
                        primaryForSearch(member),
                        primaryForRouting(member),
                        visibleSuggestion(member),
                        accessCostMinutes(member),
                        member.quality(),
                        member.distanceMeters(),
                        member.source(),
                        member.explanation()
                ));
            }
            return result;
        }

        private List<CanonicalStopAreaTransferEdge> toTransferEdges() {
            List<CanonicalStopAreaTransferEdge> result = new ArrayList<>();
            String primaryAreaId = primary.area().areaId();
            result.add(new CanonicalStopAreaTransferEdge(
                    canonicalAreaId,
                    primaryAreaId,
                    primaryAreaId,
                    0,
                    0,
                    "GOOD",
                    "SAME_CANONICAL_PRIMARY",
                    "Primary member has no internal access cost."
            ));
            for (Member member : members.values()) {
                String memberAreaId = member.area().area().areaId();
                if (memberAreaId.equals(primaryAreaId)) {
                    continue;
                }
                int minutes = accessCostMinutes(member);
                String quality = qualityForDistance(member.distanceMeters());
                result.add(new CanonicalStopAreaTransferEdge(
                        canonicalAreaId,
                        memberAreaId,
                        primaryAreaId,
                        member.distanceMeters(),
                        minutes,
                        quality,
                        "CANONICAL_FAMILY_DISTANCE",
                        "Internal family access from member to primary."
                ));
                result.add(new CanonicalStopAreaTransferEdge(
                        canonicalAreaId,
                        primaryAreaId,
                        memberAreaId,
                        member.distanceMeters(),
                        minutes,
                        quality,
                        "CANONICAL_FAMILY_DISTANCE",
                        "Internal family egress from primary to member."
                ));
            }
            return result;
        }
    }

    private static AliasDisplayCandidate aliasDisplayCandidate(StopAreaAlias alias) {
        String normalized = alias.aliasNormalized();
        String cleaned = cleanDisplayName(alias.alias());
        if (cleaned.isBlank() || normalized == null || normalized.isBlank()) {
            return AliasDisplayCandidate.empty();
        }
        if (normalized.contains("central station") || normalized.contains("flixtrain")) {
            return AliasDisplayCandidate.empty();
        }
        if (containsToken(normalized, "oben")
                || containsToken(normalized, "tief")
                || containsToken(normalized, "upper")
                || containsToken(normalized, "lower")) {
            return AliasDisplayCandidate.empty();
        }
        if (!hasUsableCity(cleaned)) {
            return AliasDisplayCandidate.empty();
        }
        if (!normalized.contains("hauptbahnhof")
                && !containsToken(normalized, "hbf")
                && !normalized.contains("bahnhof")
                && !containsToken(normalized, "bf")
                && !normalized.contains("ostbahnhof")
                && !normalized.contains("westbahnhof")
                && !normalized.contains("nordbahnhof")
                && !normalized.contains("suedbahnhof")
                && !normalized.contains("sudbahnhof")) {
            return AliasDisplayCandidate.empty();
        }

        int score = alias.priority();
        if ("CANONICAL".equals(alias.aliasType())) {
            score += 60;
        } else if ("STATION_SYNONYM".equals(alias.aliasType())) {
            score += 45;
        } else if ("RAIL_STATION_INTENT".equals(alias.aliasType())) {
            score += 35;
        }
        if ("STOP_NAME".equals(alias.source())) {
            score += 30;
        }
        if (containsToken(normalized, "hbf") || normalized.contains("hauptbahnhof")) {
            score += 25;
        }
        return new AliasDisplayCandidate(canonicalizeAliasDisplay(cleaned), score);
    }

    private static AliasDisplayCandidate specificPlaceAliasDisplayCandidate(
            StopAreaAlias alias,
            String city
    ) {
        if (city == null || city.isBlank() || alias.alias() == null || alias.alias().isBlank()) {
            return AliasDisplayCandidate.empty();
        }
        if (!Set.of("CANONICAL", "CITY_QUALIFIED", "STATION_SYNONYM", "RAIL_STATION_INTENT")
                .contains(alias.aliasType())) {
            return AliasDisplayCandidate.empty();
        }
        String cleaned = cleanDisplayName(alias.alias());
        String normalized = StopNameNormalizer.normalize(cleaned);
        String normalizedCity = StopNameNormalizer.normalize(city);
        if (normalized.isBlank()
                || normalized.equals(normalizedCity)
                || isBareStationDisplayName(cleaned)
                || isTechnicalDisplayName(cleaned)
                || containsPlatformSignal(normalized)) {
            return AliasDisplayCandidate.empty();
        }

        String value = normalized.startsWith(normalizedCity + " ")
                ? cleaned
                : cleanDisplayName(city + " " + cleaned);
        if (StopNameNormalizer.normalize(value).equals(normalizedCity)) {
            return AliasDisplayCandidate.empty();
        }

        int score = alias.priority();
        if ("CANONICAL".equals(alias.aliasType())) {
            score += 60;
        } else if ("STATION_SYNONYM".equals(alias.aliasType())) {
            score += 40;
        }
        if ("STOP_NAME".equals(alias.source())) {
            score += 35;
        } else if ("AREA_NAME".equals(alias.source())) {
            score += 20;
        }
        if (hasStationIntent(cleaned)) {
            score += 20;
        }
        return new AliasDisplayCandidate(value, score);
    }

    private static AliasDisplayCandidate genericRailCityAliasDisplayCandidate(
            StopAreaAlias alias,
            String city,
            String normalizedCity
    ) {
        if (alias.alias() == null || alias.alias().isBlank()) {
            return AliasDisplayCandidate.empty();
        }
        String normalized = StopNameNormalizer.normalize(alias.alias());
        boolean mainStation = normalized.equals(normalizedCity + " hbf")
                || normalized.equals("hbf " + normalizedCity)
                || normalized.equals(normalizedCity + " hauptbahnhof")
                || normalized.equals("hauptbahnhof " + normalizedCity);
        boolean station = normalized.equals(normalizedCity + " bahnhof")
                || normalized.equals("bahnhof " + normalizedCity)
                || normalized.equals(normalizedCity + " bf")
                || normalized.equals("bf " + normalizedCity);
        if (!mainStation && !station) {
            return AliasDisplayCandidate.empty();
        }
        int score = alias.priority() + (mainStation ? 100 : 50);
        if ("CANONICAL".equals(alias.aliasType())) {
            score += 30;
        }
        if ("STOP_NAME".equals(alias.source())) {
            score += 20;
        }
        return new AliasDisplayCandidate(city + (mainStation ? " Hbf" : " Bahnhof"), score);
    }

    private static boolean containsPlatformSignal(String normalized) {
        return containsToken(normalized, "gleis")
                || containsToken(normalized, "platform")
                || containsToken(normalized, "steig")
                || containsToken(normalized, "bahnsteig");
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

    private static String canonicalizeAliasDisplay(String value) {
        String cleaned = cleanDisplayName(value);
        String city = cityName(cleaned);
        if (city.isBlank()) {
            return cleaned;
        }
        String station = stationName(cleaned, city);
        if (StopNameNormalizer.normalize(station).contains("hauptbahnhof")) {
            return city + " Hbf";
        }
        return cleaned;
    }

    private record AliasDisplayCandidate(String value, int score) {
        private static AliasDisplayCandidate empty() {
            return new AliasDisplayCandidate("", 0);
        }
    }

    public record CanonicalStopAreaBuildResult(
            List<CanonicalStopArea> canonicalAreas,
            List<CanonicalStopAreaMember> members,
            List<CanonicalStopAreaTransferEdge> transferEdges,
            CanonicalStopAreaStats stats
    ) {
    }

    public record CanonicalStopAreaStats(
            int canonicalAreaCount,
            int memberCount,
            int familiesWithMultipleMembers,
            int busFeederMemberCount,
            int technicalDisplayNameCount
    ) {
        static CanonicalStopAreaStats from(
                List<CanonicalStopArea> canonicalAreas,
                List<CanonicalStopAreaMember> members
        ) {
            int multi = 0;
            int technical = 0;
            for (CanonicalStopArea area : canonicalAreas) {
                if (area.memberCount() > 1) {
                    multi++;
                }
                if ("TECHNICAL".equals(area.displayQuality())) {
                    technical++;
                }
            }
            int busFeeder = 0;
            for (CanonicalStopAreaMember member : members) {
                if ("BUS_FEEDER".equals(member.memberRole())) {
                    busFeeder++;
                }
            }
            return new CanonicalStopAreaStats(
                    canonicalAreas.size(),
                    members.size(),
                    multi,
                    busFeeder,
                    technical
            );
        }
    }
}
