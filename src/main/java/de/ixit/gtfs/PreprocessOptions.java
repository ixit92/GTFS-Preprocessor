package de.ixit.gtfs;

import java.nio.file.Path;
import java.util.List;

public record PreprocessOptions(
        RunMode mode,
        Path municipalityGeoJson,
        String municipalityDataVersion
) {
    public enum RunMode {
        FULL(false, false),
        CORE_ONLY(true, false),
        APP_RUNTIME(false, true);

        private final boolean skipDerivedBuilders;
        private final boolean requireAppReady;

        RunMode(boolean skipDerivedBuilders, boolean requireAppReady) {
            this.skipDerivedBuilders = skipDerivedBuilders;
            this.requireAppReady = requireAppReady;
        }
    }

    public PreprocessOptions {
        if (mode == null) {
            mode = RunMode.FULL;
        }
        municipalityDataVersion = municipalityDataVersion == null ? "" : municipalityDataVersion.trim();
        if (municipalityGeoJson == null && !municipalityDataVersion.isBlank()) {
            throw new IllegalArgumentException("Municipality data version requires a municipality GeoJSON input.");
        }
        if (municipalityGeoJson != null && municipalityDataVersion.isBlank()) {
            throw new IllegalArgumentException("Municipality GeoJSON requires a municipality data version.");
        }
    }

    public PreprocessOptions(RunMode mode) {
        this(mode, null, "");
    }

    public PreprocessOptions(boolean skipDerivedBuilders) {
        this(skipDerivedBuilders ? RunMode.CORE_ONLY : RunMode.FULL);
    }

    public static PreprocessOptions defaults() {
        return new PreprocessOptions(RunMode.FULL);
    }

    public static PreprocessOptions stressCoreOnly() {
        return new PreprocessOptions(RunMode.CORE_ONLY);
    }

    public static PreprocessOptions appRuntime() {
        return new PreprocessOptions(RunMode.APP_RUNTIME);
    }

    public PreprocessOptions withMunicipalityData(Path geoJson, String dataVersion) {
        return new PreprocessOptions(mode, geoJson, dataVersion);
    }

    public boolean skipDerivedBuilders() {
        return mode.skipDerivedBuilders;
    }

    public boolean requireAppReady() {
        return mode.requireAppReady;
    }

    public boolean buildRouteAxes() {
        return !skipDerivedBuilders() && mode != RunMode.APP_RUNTIME;
    }

    public boolean useAppRuntimeIndexes() {
        return mode == RunMode.APP_RUNTIME;
    }

    public String contractVersion() {
        return SqliteContract.CONTRACT_VERSION;
    }

    public String runMode() {
        return mode.name();
    }

    public List<String> skippedDerivedBuilders() {
        if (!skipDerivedBuilders()) {
            return List.of();
        }
        return List.of("area_route_service_summary", "stop_area_profiles", "hub_profiles", "route_axes", "transfer_rules", "transfer_edges");
    }
}
