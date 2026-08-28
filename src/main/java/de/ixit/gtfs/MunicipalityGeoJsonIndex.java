package de.ixit.gtfs;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.io.geojson.GeoJsonReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class MunicipalityGeoJsonIndex {
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final STRtree spatialIndex;
    private final int municipalityCount;
    private final String dataVersion;

    private MunicipalityGeoJsonIndex(STRtree spatialIndex, int municipalityCount, String dataVersion) {
        this.spatialIndex = spatialIndex;
        this.municipalityCount = municipalityCount;
        this.dataVersion = dataVersion;
    }

    public static MunicipalityGeoJsonIndex load(Path geoJson, String dataVersion) throws IOException {
        if (geoJson == null || !Files.isRegularFile(geoJson)) {
            throw new IllegalArgumentException("Municipality GeoJSON does not exist: " + geoJson);
        }
        if (dataVersion == null || dataVersion.isBlank()) {
            throw new IllegalArgumentException("Municipality data version must not be blank.");
        }

        ObjectMapper mapper = new ObjectMapper();
        GeoJsonReader geometryReader = new GeoJsonReader(GEOMETRY_FACTORY);
        STRtree index = new STRtree();
        int count = 0;

        try (JsonParser parser = mapper.getFactory().createParser(geoJson.toFile())) {
            while (parser.nextToken() != null) {
                if (parser.currentToken() != JsonToken.FIELD_NAME || !"features".equals(parser.currentName())) {
                    continue;
                }
                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalArgumentException("Municipality GeoJSON has no features array: " + geoJson);
                }
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    JsonNode feature = mapper.readTree(parser);
                    Municipality municipality = municipalityFrom(feature, geometryReader);
                    if (municipality == null) {
                        continue;
                    }
                    index.insert(municipality.geometry().getGeometry().getEnvelopeInternal(), municipality);
                    count++;
                }
                break;
            }
        } catch (org.locationtech.jts.io.ParseException exception) {
            throw new IOException("Invalid municipality geometry in " + geoJson, exception);
        }

        if (count == 0) {
            throw new IllegalArgumentException("Municipality GeoJSON contains no usable municipality features: " + geoJson);
        }
        index.build();
        return new MunicipalityGeoJsonIndex(index, count, dataVersion.trim());
    }

    public Optional<ResolvedMunicipality> resolve(double latitude, double longitude) {
        if (!Double.isFinite(latitude) || !Double.isFinite(longitude)
                || latitude < -90.0 || latitude > 90.0
                || longitude < -180.0 || longitude > 180.0) {
            return Optional.empty();
        }
        Point point = GEOMETRY_FACTORY.createPoint(new Coordinate(longitude, latitude));
        @SuppressWarnings("unchecked")
        List<Municipality> candidates = spatialIndex.query(point.getEnvelopeInternal());
        return candidates.stream()
                .filter(candidate -> candidate.geometry().covers(point))
                .min(Comparator.comparingDouble(candidate -> candidate.geometry().getGeometry().getArea()))
                .map(candidate -> new ResolvedMunicipality(
                        candidate.municipalityId(),
                        candidate.cityName(),
                        candidate.municipalityType(),
                        dataVersion
                ));
    }

    public int municipalityCount() {
        return municipalityCount;
    }

    public String dataVersion() {
        return dataVersion;
    }

    private static Municipality municipalityFrom(JsonNode feature, GeoJsonReader reader)
            throws org.locationtech.jts.io.ParseException {
        if (feature == null || !feature.isObject()) {
            return null;
        }
        JsonNode properties = feature.path("properties");
        JsonNode geometryNode = feature.path("geometry");
        String cityName = property(properties, "gen", "GEN", "name", "NAME");
        String municipalityId = property(properties, "ags", "AGS", "ars", "ARS");
        String municipalityType = property(properties, "bez", "BEZ", "type", "TYPE");
        if (cityName.isBlank() || municipalityId.isBlank() || geometryNode.isMissingNode() || geometryNode.isNull()) {
            return null;
        }
        Geometry geometry = reader.read(geometryNode.toString());
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        var envelope = geometry.getEnvelopeInternal();
        if (envelope.getMinX() < -180.0 || envelope.getMaxX() > 180.0
                || envelope.getMinY() < -90.0 || envelope.getMaxY() > 90.0) {
            throw new IllegalArgumentException(
                    "Municipality GeoJSON must use longitude/latitude coordinates in EPSG:4326."
            );
        }
        return new Municipality(
                municipalityId.trim(),
                cityName.trim(),
                municipalityType.trim(),
                PreparedGeometryFactory.prepare(geometry)
        );
    }

    private static String property(JsonNode properties, String... names) {
        if (properties == null || !properties.isObject()) {
            return "";
        }
        for (String name : names) {
            JsonNode value = properties.get(name);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        for (var fields = properties.fields(); fields.hasNext(); ) {
            var field = fields.next();
            for (String name : names) {
                if (field.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT))
                        && !field.getValue().asText().isBlank()) {
                    return field.getValue().asText();
                }
            }
        }
        return "";
    }

    private record Municipality(
            String municipalityId,
            String cityName,
            String municipalityType,
            PreparedGeometry geometry
    ) {
    }

    public record ResolvedMunicipality(
            String municipalityId,
            String cityName,
            String municipalityType,
            String dataVersion
    ) {
    }
}
