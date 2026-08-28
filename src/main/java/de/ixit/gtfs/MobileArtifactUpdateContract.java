package de.ixit.gtfs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

public final class MobileArtifactUpdateContract {
    public static final String DESCRIPTOR_VERSION = "0.1";
    public static final String MINIMUM_INSTALLER_VERSION = "0.9.3";
    public static final String DESCRIPTOR_FILE = "ixit-mobile-update-v0.1.json";
    public static final String SIGNATURE_FILE = "ixit-mobile-update-v0.1.sig";
    private static final long MAXIMUM_FUTURE_CLOCK_SKEW_SECONDS = 600;

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private MobileArtifactUpdateContract() {
    }

    public static MobileArtifactUpdateDescriptor create(
            Path packageDirectory,
            Path outputDirectory,
            URI downloadBaseUrl,
            String channel,
            long sequenceNumber,
            Instant publishedAt,
            Instant notBefore,
            Instant expiresAt,
            long maximumDatabaseBytes,
            PrivateKey privateKey,
            PublicKey publicKey
    ) throws IOException, GeneralSecurityException, java.sql.SQLException {
        if (privateKey == null || publicKey == null) {
            throw new IllegalArgumentException("Descriptor signing keys are required");
        }
        MobileArtifactManifest manifest = MobileArtifactPackager.verifySearchablePackage(
                packageDirectory,
                publicKey,
                maximumDatabaseBytes
        );
        validateChannel(channel);
        validateSequence(sequenceNumber);
        validateTimeline(publishedAt, notBefore, expiresAt);
        URI baseUrl = validateDownloadBaseUrl(downloadBaseUrl);

        Path packageRoot = packageDirectory.toAbsolutePath().normalize();
        MobileArtifactUpdateDescriptor descriptor = new MobileArtifactUpdateDescriptor(
                DESCRIPTOR_VERSION,
                channel,
                sequenceNumber,
                manifest.artifactId(),
                manifest.artifactProfile(),
                manifest.manifestVersion(),
                MobileArtifactPackager.SEARCHABLE_MANIFEST_FILE,
                BuildIdentity.sha256File(
                        packageRoot.resolve(MobileArtifactPackager.SEARCHABLE_MANIFEST_FILE)
                ),
                MobileArtifactPackager.SEARCHABLE_SIGNATURE_FILE,
                manifest.databaseFile(),
                manifest.databaseSha256(),
                manifest.databaseBytes(),
                baseUrl.toASCIIString(),
                publishedAt.toString(),
                notBefore.toString(),
                expiresAt.toString(),
                MINIMUM_INSTALLER_VERSION,
                MobileArtifactCrypto.SIGNATURE_ALGORITHM,
                MobileArtifactCrypto.keyId(publicKey)
        );

        Path output = outputDirectory.toAbsolutePath().normalize();
        if (Files.exists(output)) {
            throw new IllegalArgumentException("Descriptor output directory already exists: " + output);
        }
        Path parent = output.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Descriptor output directory must have a parent");
        }
        Files.createDirectories(parent);
        Path staging = parent.resolve("." + output.getFileName() + ".staging-" + UUID.randomUUID());
        Files.createDirectory(staging);
        boolean published = false;
        try {
            byte[] descriptorBytes = JSON.writeValueAsBytes(descriptor);
            Files.write(staging.resolve(DESCRIPTOR_FILE), descriptorBytes);
            Files.writeString(
                    staging.resolve(SIGNATURE_FILE),
                    Base64.getEncoder().encodeToString(
                            MobileArtifactCrypto.sign(descriptorBytes, privateKey)
                    ) + System.lineSeparator(),
                    StandardCharsets.US_ASCII
            );
            verifyDescriptor(
                    staging,
                    publicKey,
                    publishedAt.isAfter(notBefore) ? publishedAt : notBefore,
                    channel,
                    sequenceNumber - 1,
                    Set.of(baseUrl.getHost().toLowerCase(Locale.ROOT))
            );
            try {
                Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("Atomic descriptor publication is not supported for " + output, exception);
            }
            published = true;
            return descriptor;
        } finally {
            if (!published) {
                deleteStagingTree(staging, parent);
            }
        }
    }

    public static MobileArtifactUpdateDescriptor verifyDescriptor(
            Path descriptorDirectory,
            PublicKey publicKey,
            Instant now,
            String expectedChannel,
            long installedSequenceNumber,
            Set<String> allowedHosts
    ) throws IOException, GeneralSecurityException {
        if (publicKey == null || now == null) {
            throw new IllegalArgumentException("Descriptor verification key and time are required");
        }
        Path directory = descriptorDirectory.toAbsolutePath().normalize();
        Path descriptorPath = requireRegularDescriptorFile(directory, DESCRIPTOR_FILE);
        Path signaturePath = requireRegularDescriptorFile(directory, SIGNATURE_FILE);
        byte[] descriptorBytes = Files.readAllBytes(descriptorPath);
        if (descriptorBytes.length < 1 || descriptorBytes.length > 64 * 1024) {
            throw new IllegalArgumentException("Mobile update descriptor must be between 1 byte and 64 KiB");
        }
        byte[] signature;
        if (Files.size(signaturePath) < 1 || Files.size(signaturePath) > 8 * 1024) {
            throw new IllegalArgumentException("Mobile update descriptor signature file is invalid");
        }
        try {
            signature = Base64.getDecoder().decode(
                    Files.readString(signaturePath, StandardCharsets.US_ASCII).trim()
            );
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid update descriptor signature encoding", exception);
        }
        if (!MobileArtifactCrypto.verify(descriptorBytes, signature, publicKey)) {
            throw new IllegalArgumentException("Mobile update descriptor signature verification failed");
        }
        MobileArtifactUpdateDescriptor descriptor = JSON.readValue(
                descriptorBytes,
                MobileArtifactUpdateDescriptor.class
        );
        validateDescriptor(
                descriptor,
                publicKey,
                now,
                expectedChannel,
                installedSequenceNumber,
                allowedHosts
        );
        return descriptor;
    }

    public static MobileArtifactManifest verifyArtifactBinding(
            MobileArtifactUpdateDescriptor descriptor,
            Path packageDirectory,
            PublicKey publicKey,
            long maximumDatabaseBytes
    ) throws IOException, GeneralSecurityException, java.sql.SQLException {
        if (descriptor == null) {
            throw new IllegalArgumentException("Mobile update descriptor is required");
        }
        requireValue("descriptorVersion", DESCRIPTOR_VERSION, descriptor.descriptorVersion());
        requireValue("artifactProfile", MobileArtifactPackager.SEARCHABLE_ARTIFACT_PROFILE,
                descriptor.artifactProfile());
        requireValue("manifestVersion", MobileArtifactPackager.SEARCHABLE_MANIFEST_VERSION,
                descriptor.manifestVersion());
        requireValue("manifestFile", MobileArtifactPackager.SEARCHABLE_MANIFEST_FILE,
                descriptor.manifestFile());
        requireValue("manifestSignatureFile", MobileArtifactPackager.SEARCHABLE_SIGNATURE_FILE,
                descriptor.manifestSignatureFile());
        requireValue("databaseFile", MobileArtifactPackager.DATABASE_FILE, descriptor.databaseFile());
        Path directory = packageDirectory.toAbsolutePath().normalize();
        Path manifestPath = requireRegularDescriptorFile(directory, descriptor.manifestFile());
        requireValue(
                "manifestSha256",
                descriptor.manifestSha256(),
                BuildIdentity.sha256File(manifestPath)
        );
        MobileArtifactManifest manifest = MobileArtifactPackager.verifySearchablePackage(
                directory,
                publicKey,
                maximumDatabaseBytes
        );
        requireValue("artifactId", descriptor.artifactId(), manifest.artifactId());
        requireValue("artifactProfile", descriptor.artifactProfile(), manifest.artifactProfile());
        requireValue("manifestVersion", descriptor.manifestVersion(), manifest.manifestVersion());
        requireValue("databaseFile", descriptor.databaseFile(), manifest.databaseFile());
        requireValue("databaseSha256", descriptor.databaseSha256(), manifest.databaseSha256());
        if (descriptor.databaseBytes() != manifest.databaseBytes()) {
            throw new IllegalArgumentException("databaseBytes does not match the signed artifact manifest");
        }
        requireRegularDescriptorFile(directory, descriptor.manifestSignatureFile());
        return manifest;
    }

    private static void validateDescriptor(
            MobileArtifactUpdateDescriptor descriptor,
            PublicKey publicKey,
            Instant now,
            String expectedChannel,
            long installedSequenceNumber,
            Set<String> allowedHosts
    ) throws GeneralSecurityException {
        requireValue("descriptorVersion", DESCRIPTOR_VERSION, descriptor.descriptorVersion());
        requireValue("artifactProfile", MobileArtifactPackager.SEARCHABLE_ARTIFACT_PROFILE,
                descriptor.artifactProfile());
        requireValue("manifestVersion", MobileArtifactPackager.SEARCHABLE_MANIFEST_VERSION,
                descriptor.manifestVersion());
        requireValue("manifestFile", MobileArtifactPackager.SEARCHABLE_MANIFEST_FILE,
                descriptor.manifestFile());
        requireValue("manifestSignatureFile", MobileArtifactPackager.SEARCHABLE_SIGNATURE_FILE,
                descriptor.manifestSignatureFile());
        requireValue("databaseFile", MobileArtifactPackager.DATABASE_FILE, descriptor.databaseFile());
        requireValue("minimumInstallerVersion", MINIMUM_INSTALLER_VERSION,
                descriptor.minimumInstallerVersion());
        requireValue("signatureAlgorithm", MobileArtifactCrypto.SIGNATURE_ALGORITHM,
                descriptor.signatureAlgorithm());
        requireValue("keyId", MobileArtifactCrypto.keyId(publicKey), descriptor.keyId());
        validateChannel(descriptor.channel());
        requireValue("channel", expectedChannel, descriptor.channel());
        validateSequence(descriptor.sequenceNumber());
        if (descriptor.sequenceNumber() <= installedSequenceNumber) {
            throw new IllegalArgumentException("Mobile update sequence is not newer than the installed sequence");
        }
        if (!isSha256(descriptor.manifestSha256()) || !isSha256(descriptor.databaseSha256())) {
            throw new IllegalArgumentException("Mobile update descriptor contains an invalid SHA-256 value");
        }
        if (descriptor.databaseBytes() < 1) {
            throw new IllegalArgumentException("Mobile update databaseBytes must be positive");
        }
        if (descriptor.artifactId() == null
                || !descriptor.artifactId().matches("[a-z0-9][a-z0-9._-]{2,79}")) {
            throw new IllegalArgumentException("Mobile update artifactId is invalid");
        }
        Instant publishedAt = parseInstant("publishedAt", descriptor.publishedAt());
        Instant notBefore = parseInstant("notBefore", descriptor.notBefore());
        Instant expiresAt = parseInstant("expiresAt", descriptor.expiresAt());
        validateTimeline(publishedAt, notBefore, expiresAt);
        if (publishedAt.isAfter(now.plusSeconds(MAXIMUM_FUTURE_CLOCK_SKEW_SECONDS))) {
            throw new IllegalArgumentException("Mobile update publishedAt is implausibly in the future");
        }
        if (now.isBefore(notBefore)) {
            throw new IllegalArgumentException("Mobile update descriptor is not valid yet");
        }
        if (now.isAfter(expiresAt)) {
            throw new IllegalArgumentException("Mobile update descriptor has expired");
        }
        URI baseUrl = validateDownloadBaseUrl(URI.create(descriptor.downloadBaseUrl()));
        Set<String> normalizedHosts = allowedHosts == null
                ? Set.of()
                : allowedHosts.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()
                );
        if (normalizedHosts.isEmpty()
                || !normalizedHosts.contains(baseUrl.getHost().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("Mobile update download host is not allowed");
        }
    }

    private static URI validateDownloadBaseUrl(URI requested) {
        if (requested == null
                || !"https".equalsIgnoreCase(requested.getScheme())
                || requested.getHost() == null
                || requested.getHost().isBlank()
                || requested.getUserInfo() != null
                || requested.getQuery() != null
                || requested.getFragment() != null
                || requested.getPath() == null
                || !requested.getPath().endsWith("/")) {
            throw new IllegalArgumentException(
                    "downloadBaseUrl must be an HTTPS directory URL without credentials, query or fragment"
            );
        }
        try {
            return new URI(
                    "https",
                    null,
                    requested.getHost().toLowerCase(Locale.ROOT),
                    requested.getPort(),
                    requested.getPath(),
                    null,
                    null
            );
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("downloadBaseUrl is invalid", exception);
        }
    }

    private static void validateTimeline(Instant publishedAt, Instant notBefore, Instant expiresAt) {
        if (publishedAt == null || notBefore == null || expiresAt == null
                || expiresAt.isBefore(publishedAt) || !expiresAt.isAfter(notBefore)) {
            throw new IllegalArgumentException("Mobile update validity timeline is invalid");
        }
    }

    private static Instant parseInstant(String label, String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeException | NullPointerException exception) {
            throw new IllegalArgumentException("Mobile update " + label + " is invalid", exception);
        }
    }

    private static void validateChannel(String channel) {
        if (channel == null || !channel.matches("[a-z0-9][a-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("Mobile update channel is invalid");
        }
    }

    private static void validateSequence(long sequenceNumber) {
        if (sequenceNumber < 1) {
            throw new IllegalArgumentException("Mobile update sequenceNumber must be positive");
        }
    }

    private static void requireValue(String label, String expected, String actual) {
        if (expected == null || !expected.equals(actual)) {
            throw new IllegalArgumentException(label + " must be " + expected + " but was "
                    + (actual == null || actual.isBlank() ? "<missing>" : actual));
        }
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static Path requireRegularDescriptorFile(Path directory, String name) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || Files.isSymbolicLink(root)) {
            throw new IllegalArgumentException("Mobile descriptor/package directory is missing or symbolic");
        }
        Path child = root.resolve(name).normalize();
        if (!child.getParent().equals(root)
                || !Files.isRegularFile(child)
                || Files.isSymbolicLink(child)
                || !child.toRealPath().getParent().equals(root.toRealPath())) {
            throw new IllegalArgumentException("Mobile descriptor/package file is missing or outside directory: "
                    + name);
        }
        return child;
    }

    private static void deleteStagingTree(Path staging, Path expectedParent) throws IOException {
        Path normalized = staging.toAbsolutePath().normalize();
        if (!normalized.getParent().equals(expectedParent.toAbsolutePath().normalize())
                || !normalized.getFileName().toString().contains(".staging-")) {
            throw new IOException("Refusing to delete unexpected descriptor staging path: " + normalized);
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
