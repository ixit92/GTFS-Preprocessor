package de.ixit.gtfs;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

@JsonPropertyOrder({
        "descriptorVersion",
        "channel",
        "sequenceNumber",
        "artifactId",
        "artifactProfile",
        "manifestVersion",
        "manifestFile",
        "manifestSha256",
        "manifestSignatureFile",
        "databaseFile",
        "databaseSha256",
        "databaseBytes",
        "downloadBaseUrl",
        "publishedAt",
        "notBefore",
        "expiresAt",
        "minimumInstallerVersion",
        "signatureAlgorithm",
        "keyId"
})
public record MobileArtifactUpdateDescriptor(
        String descriptorVersion,
        String channel,
        long sequenceNumber,
        String artifactId,
        String artifactProfile,
        String manifestVersion,
        String manifestFile,
        String manifestSha256,
        String manifestSignatureFile,
        String databaseFile,
        String databaseSha256,
        long databaseBytes,
        String downloadBaseUrl,
        String publishedAt,
        String notBefore,
        String expiresAt,
        String minimumInstallerVersion,
        String signatureAlgorithm,
        String keyId
) {
}
