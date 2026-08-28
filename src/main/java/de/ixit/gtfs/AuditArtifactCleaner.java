package de.ixit.gtfs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AuditArtifactCleaner {
    public static final String LIFECYCLE_VERSION = "0.7.4";
    public static final String REPORT_NAME = "artifact-retention-report-v0.7.4.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private AuditArtifactCleaner() {
    }

    public static AuditArtifactCleanupReport clean(
            Path toolRoot,
            Path auditRoot,
            boolean execute,
            boolean deleteInputCopies
    ) throws IOException {
        SafePaths paths = validateRoots(toolRoot, auditRoot);
        Path outputDirectory = paths.auditRoot().resolve("output");
        requirePassingAudit(outputDirectory);

        List<Path> candidates = new ArrayList<>();
        addCandidates(outputDirectory, candidates, true);
        if (deleteInputCopies) {
            addCandidates(paths.auditRoot().resolve(Path.of("local-data", "from-routing-cache")), candidates, false);
        }
        candidates.sort(Comparator.comparing(Path::toString));

        long candidateBytes = 0;
        for (Path candidate : candidates) {
            candidateBytes += Files.size(candidate);
        }
        List<String> retainedEvidence = retainedEvidence(paths.auditRoot(), candidates);
        Path reportPath = outputDirectory.resolve(REPORT_NAME);

        AuditArtifactCleanupReport planned = report(
                paths.auditRoot(),
                execute,
                deleteInputCopies,
                candidateBytes,
                0,
                0,
                retainedEvidence,
                candidates,
                execute ? "PENDING_DELETE" : "DRY_RUN",
                List.of(),
                true
        );
        writeReport(reportPath, planned);
        if (!execute) {
            return planned;
        }

        List<AuditArtifactCleanupReport.Candidate> results = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int deletedCount = 0;
        long deletedBytes = 0;
        for (Path candidate : candidates) {
            long size = Files.size(candidate);
            String relative = paths.auditRoot().relativize(candidate).toString().replace('\\', '/');
            try {
                Files.delete(candidate);
                deletedCount++;
                deletedBytes += size;
                results.add(new AuditArtifactCleanupReport.Candidate(relative, size, "DELETED"));
            } catch (IOException ex) {
                errors.add(relative + ": " + ex.getMessage());
                results.add(new AuditArtifactCleanupReport.Candidate(relative, size, "DELETE_FAILED"));
            }
        }

        AuditArtifactCleanupReport completed = new AuditArtifactCleanupReport(
                LIFECYCLE_VERSION,
                Instant.now().toString(),
                paths.auditRoot().toString(),
                true,
                deleteInputCopies,
                true,
                candidates.size(),
                candidateBytes,
                deletedCount,
                deletedBytes,
                retainedEvidence,
                List.copyOf(results),
                List.copyOf(errors),
                errors.isEmpty()
        );
        writeReport(reportPath, completed);
        if (!errors.isEmpty()) {
            throw new IOException("Audit artifact cleanup incomplete: " + String.join("; ", errors));
        }
        return completed;
    }

    private static SafePaths validateRoots(Path requestedToolRoot, Path requestedAuditRoot) throws IOException {
        Path toolRoot = requestedToolRoot.toAbsolutePath().normalize();
        Path auditRoot = requestedAuditRoot.toAbsolutePath().normalize();
        Path buildRoot = toolRoot.resolve("build").normalize();
        if (!Files.isDirectory(toolRoot) || !Files.isDirectory(buildRoot) || !Files.isDirectory(auditRoot)) {
            throw new IllegalArgumentException("Tool, build and audit roots must already exist");
        }
        if (!auditRoot.startsWith(buildRoot) || auditRoot.equals(buildRoot)) {
            throw new IllegalArgumentException("Audit root must be a child of the tool build directory: " + auditRoot);
        }
        if (Files.isSymbolicLink(auditRoot)) {
            throw new IllegalArgumentException("Audit root must not be a symbolic link: " + auditRoot);
        }
        Path realBuildRoot = buildRoot.toRealPath();
        Path realAuditRoot = auditRoot.toRealPath();
        if (!realAuditRoot.startsWith(realBuildRoot) || realAuditRoot.equals(realBuildRoot)) {
            throw new IllegalArgumentException("Resolved audit root escapes the tool build directory: " + realAuditRoot);
        }
        return new SafePaths(toolRoot, realAuditRoot);
    }

    private static void requirePassingAudit(Path outputDirectory) throws IOException {
        if (!Files.isDirectory(outputDirectory) || Files.isSymbolicLink(outputDirectory)) {
            throw new IllegalArgumentException("Audit output must be a regular directory: " + outputDirectory);
        }
        Path status = outputDirectory.resolve("status.txt");
        if (!Files.isRegularFile(status) || Files.isSymbolicLink(status)) {
            throw new IllegalArgumentException("Missing regular audit status file: " + status);
        }
        String statusText = Files.readString(status).stripLeading();
        if (!statusText.startsWith("PASS")) {
            throw new IllegalArgumentException("Cleanup requires PASS status: " + status);
        }

        Path auditReport;
        try (var files = Files.list(outputDirectory)) {
            auditReport = files
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().startsWith("service-day-real-feed-audit-v"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException("Missing service-day real-feed audit JSON in " + outputDirectory));
        }
        JsonNode root = JSON.readTree(auditReport.toFile());
        if (root.path("pass").asBoolean(false)) {
            return;
        }

        Path driftReport;
        try (var files = Files.list(outputDirectory)) {
            driftReport = files
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> path.getFileName().toString().startsWith("real-feed-drift-audit-v"))
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .max(Comparator.comparing(path -> path.getFileName().toString()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Cleanup requires a passing service-day or real-feed drift audit JSON in " + outputDirectory
                    ));
        }
        JsonNode drift = JSON.readTree(driftReport.toFile());
        boolean driftApproved = drift.path("pass").asBoolean(false)
                && "ELIGIBLE_FOR_MANUAL_REVIEW".equals(drift.path("baselinePromotionState").asText())
                && "ROW_COUNT_DRIFT_ONLY".equals(drift.path("candidateAuditCompatibility").asText())
                && drift.path("failures").isArray()
                && drift.path("failures").isEmpty();
        if (!driftApproved) {
            throw new IllegalArgumentException(
                    "Cleanup requires a passing service-day audit or an approved row-count-only drift audit: "
                            + driftReport
            );
        }
    }

    private static void addCandidates(Path directory, List<Path> candidates, boolean includeSqlite) throws IOException {
        if (!Files.isDirectory(directory) || Files.isSymbolicLink(directory)) {
            return;
        }
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> isCandidate(path, includeSqlite))
                    .forEach(path -> candidates.add(path.toAbsolutePath().normalize()));
        }
    }

    private static boolean isCandidate(Path path, boolean includeSqlite) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")) {
            return true;
        }
        return includeSqlite
                && (name.endsWith(".sqlite") || name.endsWith(".sqlite-wal") || name.endsWith(".sqlite-shm"));
    }

    private static List<String> retainedEvidence(Path auditRoot, List<Path> candidates) throws IOException {
        List<String> retained = new ArrayList<>();
        try (var files = Files.walk(auditRoot)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !candidates.contains(path.toAbsolutePath().normalize()))
                    .map(path -> auditRoot.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .forEach(retained::add);
        }
        return List.copyOf(retained);
    }

    private static AuditArtifactCleanupReport report(
            Path auditRoot,
            boolean execute,
            boolean deleteInputCopies,
            long candidateBytes,
            int deletedCount,
            long deletedBytes,
            List<String> retainedEvidence,
            List<Path> candidates,
            String action,
            List<String> errors,
            boolean pass
    ) throws IOException {
        List<AuditArtifactCleanupReport.Candidate> entries = new ArrayList<>();
        for (Path candidate : candidates) {
            entries.add(new AuditArtifactCleanupReport.Candidate(
                    auditRoot.relativize(candidate).toString().replace('\\', '/'),
                    Files.size(candidate),
                    action
            ));
        }
        return new AuditArtifactCleanupReport(
                LIFECYCLE_VERSION,
                Instant.now().toString(),
                auditRoot.toString(),
                execute,
                deleteInputCopies,
                true,
                candidates.size(),
                candidateBytes,
                deletedCount,
                deletedBytes,
                retainedEvidence,
                List.copyOf(entries),
                List.copyOf(errors),
                pass
        );
    }

    private static void writeReport(Path reportPath, AuditArtifactCleanupReport report) throws IOException {
        Files.createDirectories(reportPath.getParent());
        JSON.writeValue(reportPath.toFile(), report);
    }

    private record SafePaths(Path toolRoot, Path auditRoot) {
    }
}
