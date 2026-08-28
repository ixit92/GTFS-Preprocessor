package de.ixit.gtfs;

import de.ixit.gtfs.model.StopArea;
import de.ixit.gtfs.model.StopAreaCity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class StopAreaCityBuilder {
    private StopAreaCityBuilder() {
    }

    public static StopAreaCityBuildResult build(
            List<StopArea> stopAreas,
            Path municipalityGeoJson,
            String municipalityDataVersion
    ) throws IOException {
        MunicipalityGeoJsonIndex index = municipalityGeoJson == null
                ? null
                : MunicipalityGeoJsonIndex.load(municipalityGeoJson, municipalityDataVersion);
        ArrayList<StopAreaCity> cities = new ArrayList<>(stopAreas.size());
        int officialBoundaryCount = 0;
        int nameFallbackCount = 0;
        int unresolvedCount = 0;

        for (StopArea area : stopAreas) {
            Optional<MunicipalityGeoJsonIndex.ResolvedMunicipality> resolved = resolve(index, area);
            if (resolved.isPresent()) {
                MunicipalityGeoJsonIndex.ResolvedMunicipality municipality = resolved.get();
                cities.add(new StopAreaCity(
                        area.areaId(),
                        municipality.municipalityId(),
                        municipality.cityName(),
                        municipality.municipalityType(),
                        "BKG_VG250_GEOMETRY",
                        "OFFICIAL_BOUNDARY",
                        municipality.dataVersion(),
                        "point_in_polygon; lat=" + area.areaLat() + "; lon=" + area.areaLon()
                ));
                officialBoundaryCount++;
                continue;
            }

            String fallback = safeNameFallback(area.areaName());
            if (!fallback.isBlank()) {
                cities.add(new StopAreaCity(
                        area.areaId(),
                        "",
                        fallback,
                        "",
                        "GTFS_NAME_FALLBACK",
                        "INFERRED",
                        "",
                        "No municipality geometry match; inferred from station-form GTFS name"
                ));
                nameFallbackCount++;
            } else {
                cities.add(new StopAreaCity(
                        area.areaId(),
                        "",
                        "",
                        "",
                        "UNRESOLVED",
                        "UNRESOLVED",
                        "",
                        area.areaLat() == null || area.areaLon() == null
                                ? "Missing coordinates and no safe station-form name fallback"
                                : "No municipality geometry configured or matched"
                ));
                unresolvedCount++;
            }
        }

        return new StopAreaCityBuildResult(
                List.copyOf(cities),
                new StopAreaCityStats(
                        cities.size(),
                        officialBoundaryCount,
                        nameFallbackCount,
                        unresolvedCount,
                        index == null ? 0 : index.municipalityCount(),
                        index == null ? "" : index.dataVersion()
                )
        );
    }

    private static Optional<MunicipalityGeoJsonIndex.ResolvedMunicipality> resolve(
            MunicipalityGeoJsonIndex index,
            StopArea area
    ) {
        if (index == null || area.areaLat() == null || area.areaLon() == null) {
            return Optional.empty();
        }
        return index.resolve(area.areaLat(), area.areaLon());
    }

    private static String safeNameFallback(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String normalized = StopNameNormalizer.normalize(name);
        boolean stationForm = name.contains(",")
                || (" " + normalized + " ").contains(" hbf ")
                || normalized.contains("hauptbahnhof")
                || normalized.contains("bahnhof")
                || (" " + normalized + " ").contains(" bf ");
        return stationForm ? StopAreaNameHarmonizer.cityName(name) : "";
    }

    public record StopAreaCityBuildResult(List<StopAreaCity> cities, StopAreaCityStats stats) {
    }

    public record StopAreaCityStats(
            int totalCount,
            int officialBoundaryCount,
            int nameFallbackCount,
            int unresolvedCount,
            int municipalityFeatureCount,
            String municipalityDataVersion
    ) {
    }
}
