package de.ixit.gtfs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

public final class MobileArtifactCrypto {
    public static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    private MobileArtifactCrypto() {
    }

    public static byte[] sign(byte[] payload, PrivateKey privateKey) throws GeneralSecurityException {
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initSign(privateKey);
        signature.update(payload);
        return signature.sign();
    }

    public static boolean verify(byte[] payload, byte[] signatureBytes, PublicKey publicKey)
            throws GeneralSecurityException {
        Signature signature = Signature.getInstance(SIGNATURE_ALGORITHM);
        signature.initVerify(publicKey);
        signature.update(payload);
        return signature.verify(signatureBytes);
    }

    public static String keyId(PublicKey publicKey) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded());
        return HexFormat.of().formatHex(digest);
    }

    public static PrivateKey readPrivateKey(Path path) throws IOException, GeneralSecurityException {
        byte[] encoded = readKeyBytes(path, "PRIVATE KEY");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    public static PublicKey readPublicKey(Path path) throws IOException, GeneralSecurityException {
        byte[] encoded = readKeyBytes(path, "PUBLIC KEY");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    private static byte[] readKeyBytes(Path path, String keyType) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        String text = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (!text.startsWith("-----BEGIN")) {
            return bytes;
        }
        String base64 = text
                .replace("-----BEGIN " + keyType + "-----", "")
                .replace("-----END " + keyType + "-----", "")
                .replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid PEM encoding: " + path, exception);
        }
    }
}
