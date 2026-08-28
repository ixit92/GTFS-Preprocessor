package de.ixit.gtfs;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;

public final class MobileArtifactUpdateContractCli {
    private MobileArtifactUpdateContractCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            PrivateKey privateKey = MobileArtifactCrypto.readPrivateKey(options.privateKey());
            PublicKey publicKey = MobileArtifactCrypto.readPublicKey(options.publicKey());
            MobileArtifactUpdateDescriptor descriptor = MobileArtifactUpdateContract.create(
                    options.packageDirectory(),
                    options.outputDirectory(),
                    options.downloadBaseUrl(),
                    options.channel(),
                    options.sequenceNumber(),
                    Instant.now(),
                    options.notBefore(),
                    options.expiresAt(),
                    options.maximumBytes(),
                    privateKey,
                    publicKey
            );
            System.out.println("Mobile update contract v0.1: PASS");
            System.out.println("artifact_id=" + descriptor.artifactId());
            System.out.println("channel=" + descriptor.channel());
            System.out.println("sequence_number=" + descriptor.sequenceNumber());
            System.out.println("download_base_url=" + descriptor.downloadBaseUrl());
            System.out.println("expires_at=" + descriptor.expiresAt());
            System.out.println("output=" + options.outputDirectory());
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Mobile update contract creation failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private record Options(
            Path packageDirectory,
            Path outputDirectory,
            URI downloadBaseUrl,
            String channel,
            long sequenceNumber,
            Instant notBefore,
            Instant expiresAt,
            long maximumBytes,
            Path privateKey,
            Path publicKey
    ) {
        private static Options parse(String[] args) {
            Path toolRoot = detectToolRoot();
            Path packageDirectory = null;
            Path outputDirectory = null;
            URI downloadBaseUrl = null;
            String channel = "stable";
            long sequenceNumber = 0;
            Instant notBefore = Instant.now();
            Instant expiresAt = null;
            long maximumBytes = MobileArtifactPackager.DEFAULT_MAX_BYTES;
            Path privateKey = null;
            Path publicKey = null;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--tool-root" -> toolRoot = Path.of(requireValue(args, ++index, "--tool-root"))
                            .toAbsolutePath().normalize();
                    case "--package-dir" -> packageDirectory = Path.of(
                            requireValue(args, ++index, "--package-dir")
                    );
                    case "--output-dir" -> outputDirectory = Path.of(
                            requireValue(args, ++index, "--output-dir")
                    );
                    case "--download-base-url" -> downloadBaseUrl = URI.create(
                            requireValue(args, ++index, "--download-base-url")
                    );
                    case "--channel" -> channel = requireValue(args, ++index, "--channel");
                    case "--sequence" -> sequenceNumber = Long.parseLong(
                            requireValue(args, ++index, "--sequence")
                    );
                    case "--not-before" -> notBefore = Instant.parse(
                            requireValue(args, ++index, "--not-before")
                    );
                    case "--expires-at" -> expiresAt = Instant.parse(
                            requireValue(args, ++index, "--expires-at")
                    );
                    case "--max-bytes" -> maximumBytes = Long.parseLong(
                            requireValue(args, ++index, "--max-bytes")
                    );
                    case "--private-key" -> privateKey = Path.of(
                            requireValue(args, ++index, "--private-key")
                    );
                    case "--public-key" -> publicKey = Path.of(
                            requireValue(args, ++index, "--public-key")
                    );
                    case "--help", "-h" -> throw new IllegalArgumentException(
                            "IXIT v0.9.3 Mobile Update Contract"
                    );
                    default -> throw new IllegalArgumentException(
                            "Unknown mobile-update-contract option: " + args[index]
                    );
                }
            }
            if (!Files.isDirectory(toolRoot)) {
                throw new IllegalArgumentException("Tool root does not exist: " + toolRoot);
            }
            if (packageDirectory == null || outputDirectory == null || downloadBaseUrl == null
                    || sequenceNumber < 1 || expiresAt == null || privateKey == null || publicKey == null) {
                throw new IllegalArgumentException("Missing required mobile-update-contract option");
            }
            return new Options(
                    confined(toolRoot, packageDirectory, "package-dir"),
                    confined(toolRoot, outputDirectory, "output-dir"),
                    downloadBaseUrl,
                    channel,
                    sequenceNumber,
                    notBefore,
                    expiresAt,
                    maximumBytes,
                    confined(toolRoot, privateKey, "private-key"),
                    confined(toolRoot, publicKey, "public-key")
            );
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static Path confined(Path root, Path requested, String label) {
            Path resolved = requested.isAbsolute()
                    ? requested.toAbsolutePath().normalize()
                    : root.resolve(requested).normalize();
            if (!resolved.startsWith(root)) {
                throw new IllegalArgumentException(label + " must stay inside tool root: " + requested);
            }
            return resolved;
        }

        private static Path detectToolRoot() {
            try {
                Path location = Path.of(MobileArtifactUpdateContractCli.class.getProtectionDomain()
                        .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
                if (Files.isRegularFile(location)) {
                    return location.getParent().getParent().toAbsolutePath().normalize();
                }
                if (location.endsWith(Path.of("target", "classes"))) {
                    return location.getParent().getParent().toAbsolutePath().normalize();
                }
            } catch (URISyntaxException | NullPointerException exception) {
                // Fall through to the process directory.
            }
            return Path.of("").toAbsolutePath().normalize();
        }

        private static String usage() {
            return "Usage: mobile-update-contract --tool-root PATH --package-dir PATH "
                    + "--output-dir PATH --download-base-url HTTPS_URL --sequence N "
                    + "--expires-at INSTANT --private-key PATH --public-key PATH "
                    + "[--channel stable] [--not-before INSTANT] [--max-bytes N]";
        }
    }
}
