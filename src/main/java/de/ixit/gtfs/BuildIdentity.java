package de.ixit.gtfs;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

public record BuildIdentity(
        String identityVersion,
        String buildIdentitySha256,
        String sourceGtfsSha256,
        String preprocessorArtifactSha256,
        String preprocessorArtifactKind,
        String municipalityDataSha256,
        String runMode,
        String contractVersion
) {
    public static final String IDENTITY_VERSION = "1";
    public static final String NOT_PROVIDED = "not_provided";

    public static BuildIdentity capture(Path inputZip, PreprocessOptions options) throws IOException {
        PreprocessOptions effectiveOptions = options == null ? PreprocessOptions.defaults() : options;
        ArtifactIdentity artifact = artifactIdentity();
        String sourceSha256 = sha256File(inputZip);
        String municipalitySha256 = effectiveOptions.municipalityGeoJson() == null
                ? NOT_PROVIDED
                : sha256File(effectiveOptions.municipalityGeoJson());
        String payload = String.join("\n",
                "identity_version=" + IDENTITY_VERSION,
                "schema_version=" + SqliteContract.SCHEMA_VERSION,
                "contract_version=" + effectiveOptions.contractVersion(),
                "preprocessor_version=" + SqliteContract.PREPROCESSOR_VERSION,
                "run_mode=" + effectiveOptions.runMode(),
                "source_gtfs_sha256=" + sourceSha256,
                "preprocessor_artifact_sha256=" + artifact.sha256(),
                "municipality_data_version=" + effectiveOptions.municipalityDataVersion(),
                "municipality_data_sha256=" + municipalitySha256
        );
        return new BuildIdentity(
                IDENTITY_VERSION,
                sha256Bytes(payload.getBytes(StandardCharsets.UTF_8)),
                sourceSha256,
                artifact.sha256(),
                artifact.kind(),
                municipalitySha256,
                effectiveOptions.runMode(),
                effectiveOptions.contractVersion()
        );
    }

    public Map<String, String> metadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("build_identity_version", identityVersion);
        metadata.put("build_identity_sha256", buildIdentitySha256);
        metadata.put("source_gtfs_sha256", sourceGtfsSha256);
        metadata.put("preprocessor_artifact_sha256", preprocessorArtifactSha256);
        metadata.put("preprocessor_artifact_kind", preprocessorArtifactKind);
        metadata.put("municipality_data_sha256", municipalityDataSha256);
        metadata.put("run_mode", runMode);
        return Map.copyOf(metadata);
    }

    static String sha256File(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Build identity input is not a regular file: " + path);
        }
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static ArtifactIdentity artifactIdentity() throws IOException {
        Path location;
        try {
            location = Path.of(BuildIdentity.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | NullPointerException exception) {
            throw new IOException("Cannot resolve the running preprocessor artifact", exception);
        }
        if (Files.isRegularFile(location)) {
            return new ArtifactIdentity(sha256File(location), "JAR");
        }
        if (Files.isDirectory(location)) {
            return new ArtifactIdentity(sha256Directory(location), "CLASSES_DIRECTORY");
        }
        throw new IOException("Unsupported preprocessor code location: " + location);
    }

    private static String sha256Directory(Path directory) throws IOException {
        MessageDigest digest = sha256Digest();
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> directory.relativize(path).toString()))
                    .toList()) {
                String relative = directory.relativize(file).toString().replace('\\', '/');
                digest.update(relative.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
                try (InputStream input = Files.newInputStream(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        if (read > 0) {
                            digest.update(buffer, 0, read);
                        }
                    }
                }
                digest.update((byte) 0);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256Bytes(byte[] bytes) {
        MessageDigest digest = sha256Digest();
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record ArtifactIdentity(String sha256, String kind) {
    }
}
