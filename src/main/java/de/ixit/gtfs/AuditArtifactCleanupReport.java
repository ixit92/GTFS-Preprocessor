package de.ixit.gtfs;

import java.util.List;

public record AuditArtifactCleanupReport(
        String lifecycleVersion,
        String generatedAt,
        String auditRoot,
        boolean execute,
        boolean deleteInputCopies,
        boolean sourceAuditPass,
        int candidateCount,
        long candidateBytes,
        int deletedCount,
        long deletedBytes,
        List<String> retainedEvidence,
        List<Candidate> candidates,
        List<String> errors,
        boolean pass
) {
    public record Candidate(String path, long sizeBytes, String action) {
    }
}
