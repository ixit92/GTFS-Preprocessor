package de.ixit.gtfs;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class GtfsZipReader implements AutoCloseable {
    private final ZipFile zipFile;
    private final Map<String, ZipEntry> entriesByName;

    private GtfsZipReader(ZipFile zipFile) {
        this.zipFile = zipFile;
        this.entriesByName = new HashMap<>();
        zipFile.stream()
                .filter(entry -> !entry.isDirectory())
                .forEach(entry -> entriesByName.put(normalizeName(entry.getName()), entry));
    }

    public static GtfsZipReader open(Path path) throws IOException {
        return new GtfsZipReader(new ZipFile(path.toFile()));
    }

    public boolean exists(String gtfsFileName) {
        return entriesByName.containsKey(normalizeName(gtfsFileName));
    }

    public InputStream openRequired(String gtfsFileName) throws IOException {
        ZipEntry entry = entriesByName.get(normalizeName(gtfsFileName));
        if (entry == null) {
            throw new IllegalArgumentException("Required GTFS file missing: " + gtfsFileName);
        }
        return new BufferedInputStream(zipFile.getInputStream(entry));
    }

    @Override
    public void close() throws IOException {
        zipFile.close();
    }

    private static String normalizeName(String name) {
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) {
            normalized = normalized.substring(slash + 1);
        }
        return normalized.toLowerCase();
    }
}
