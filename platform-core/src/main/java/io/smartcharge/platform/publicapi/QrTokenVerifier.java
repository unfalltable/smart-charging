package io.smartcharge.platform.publicapi;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
final class QrTokenVerifier {
    private final byte[] secret;

    QrTokenVerifier(@Value("${charging.qr-signing-secret}") String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException("QR signing secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    VerifiedQr verify(String token) {
        String[] fields = token.split("\\.", -1);
        if (fields.length != 4 || !"sc1".equals(fields[0])) {
            throw new IllegalArgumentException("Unsupported charging QR code");
        }
        UUID tenantId;
        UUID connectorId;
        byte[] signature;
        try {
            tenantId = UUID.fromString(fields[1]);
            connectorId = UUID.fromString(fields[2]);
            signature = HexFormat.of().parseHex(fields[3]);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("Malformed charging QR code");
        }
        byte[] expected = sign(fields[0] + "." + fields[1] + "." + fields[2]);
        if (!MessageDigest.isEqual(expected, signature)) {
            throw new IllegalArgumentException("Charging QR signature is invalid");
        }
        return new VerifiedQr(tenantId, connectorId);
    }

    private byte[] sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to verify charging QR code", exception);
        }
    }

    record VerifiedQr(UUID tenantId, UUID connectorId) { }
}
