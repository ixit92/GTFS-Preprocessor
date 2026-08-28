package de.ixit.gtfs;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

@JsonPropertyOrder({
        "manifestVersion",
        "packagerVersion",
        "artifactProfile",
        "artifactId",
        "generatedAt",
        "databaseFile",
        "databaseSha256",
        "databaseBytes",
        "sourceDatabaseSha256",
        "sourceContractVersion",
        "sourcePreprocessorVersion",
        "sourceBuildIdentitySha256",
        "seedAreaIds",
        "rowCounts",
        "signatureAlgorithm",
        "keyId"
})
public record MobileArtifactManifest(
        String manifestVersion,
        String packagerVersion,
        String artifactProfile,
        String artifactId,
        String generatedAt,
        String databaseFile,
        String databaseSha256,
        long databaseBytes,
        String sourceDatabaseSha256,
        String sourceContractVersion,
        String sourcePreprocessorVersion,
        String sourceBuildIdentitySha256,
        List<String> seedAreaIds,
        Map<String, Long> rowCounts,
        String signatureAlgorithm,
        String keyId
) {
}
