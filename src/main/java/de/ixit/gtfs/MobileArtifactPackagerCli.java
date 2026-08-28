package de.ixit.gtfs;

import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

public final class MobileArtifactPackagerCli {
    private MobileArtifactPackagerCli() {
    }

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            PrivateKey privateKey = MobileArtifactCrypto.readPrivateKey(options.privateKey());
            PublicKey publicKey = MobileArtifactCrypto.readPublicKey(options.publicKey());
            MobileArtifactManifest manifest = options.searchable()
                    ? MobileArtifactPackager.packageSearchableArtifact(
                            options.database(),
                            options.outputDirectory(),
                            options.seedAreaIds(),
                            options.artifactId(),
                            options.maximumBytes(),
                            privateKey,
                            publicKey
                    )
                    : MobileArtifactPackager.packageArtifact(
                            options.database(),
                            options.outputDirectory(),
                            options.seedAreaIds(),
                            options.artifactId(),
                            options.maximumBytes(),
                            privateKey,
                            publicKey
                    );
            System.out.println("Mobile artifact package v" + manifest.packagerVersion() + ": PASS");
            System.out.println("artifact_id=" + manifest.artifactId());
            System.out.println("artifact_profile=" + manifest.artifactProfile());
            System.out.println("database_bytes=" + manifest.databaseBytes());
            System.out.println("database_sha256=" + manifest.databaseSha256());
            System.out.println("seed_areas=" + manifest.seedAreaIds().size());
            System.out.println("output=" + options.outputDirectory());
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            System.err.println(Options.usage());
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("Mobile artifact packaging failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private record Options(
            Path database,
            Path outputDirectory,
            List<String> seedAreaIds,
            String artifactId,
            long maximumBytes,
            Path privateKey,
            Path publicKey,
            boolean searchable
    ) {
        private static Options parse(String[] args) {
            Path toolRoot = detectToolRoot();
            Path database = null;
            Path output = null;
            Path privateKey = null;
            Path publicKey = null;
            List<String> seeds = new ArrayList<>();
            String artifactId = null;
            long maximumBytes = MobileArtifactPackager.DEFAULT_MAX_BYTES;
            boolean searchable = false;

            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--tool-root" -> toolRoot = Path.of(requireValue(args, ++index, "--tool-root"))
                            .toAbsolutePath().normalize();
                    case "--database" -> database = Path.of(requireValue(args, ++index, "--database"));
                    case "--output-dir" -> output = Path.of(requireValue(args, ++index, "--output-dir"));
                    case "--seed-area" -> seeds.add(requireValue(args, ++index, "--seed-area"));
                    case "--artifact-id" -> artifactId = requireValue(args, ++index, "--artifact-id");
                    case "--max-bytes" -> maximumBytes = Long.parseLong(
                            requireValue(args, ++index, "--max-bytes"));
                    case "--private-key" -> privateKey = Path.of(requireValue(args, ++index, "--private-key"));
                    case "--public-key" -> publicKey = Path.of(requireValue(args, ++index, "--public-key"));
                    case "--profile" -> {
                        String profile = requireValue(args, ++index, "--profile");
                        if ("searchable-v2".equalsIgnoreCase(profile)
                                || MobileArtifactPackager.SEARCHABLE_ARTIFACT_PROFILE.equals(profile)) {
                            searchable = true;
                        } else if (!"routing-v1".equalsIgnoreCase(profile)
                                && !MobileArtifactPackager.ARTIFACT_PROFILE.equals(profile)) {
                            throw new IllegalArgumentException("Unknown mobile artifact profile: " + profile);
                        }
                    }
                    case "--help", "-h" -> throw new IllegalArgumentException(
                            "IXIT v0.9.1 Mobile Artifact Packager");
                    default -> throw new IllegalArgumentException(
                            "Unknown mobile-package option: " + args[index]);
                }
            }
            if (!Files.isDirectory(toolRoot)) {
                throw new IllegalArgumentException("Tool root does not exist: " + toolRoot);
            }
            if (database == null || output == null || artifactId == null
                    || privateKey == null || publicKey == null || seeds.isEmpty()) {
                throw new IllegalArgumentException("Missing required mobile-package option");
            }
            return new Options(
                    confined(toolRoot, database, "database"),
                    confined(toolRoot, output, "output-dir"),
                    List.copyOf(seeds),
                    artifactId,
                    maximumBytes,
                    confined(toolRoot, privateKey, "private-key"),
                    confined(toolRoot, publicKey, "public-key"),
                    searchable
            );
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

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length || args[index].startsWith("--")) {
                throw new IllegalArgumentException("Missing value for " + option);
            }
            return args[index];
        }

        private static Path detectToolRoot() {
            try {
                Path location = Path.of(MobileArtifactPackagerCli.class.getProtectionDomain()
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
            return "Usage: mobile-package --tool-root PATH --database PATH --output-dir PATH "
                    + "--seed-area AREA_ID [--seed-area AREA_ID ...] --artifact-id ID "
                    + "--private-key PATH --public-key PATH [--max-bytes N] "
                    + "[--profile routing-v1|searchable-v2]";
        }
    }
}
