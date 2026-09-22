package io.smartcharge.platform.publicapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class QrTokenVerifierTest {
    private static final String SECRET = "test-qr-secret-with-at-least-32-characters";
    private final QrTokenVerifier verifier = new QrTokenVerifier(SECRET);

    @Test
    void verifiesSignedTenantAndConnector() throws Exception {
        UUID tenant = UUID.randomUUID();
        UUID connector = UUID.randomUUID();
        String payload = "sc1." + tenant + "." + connector;
        String token = payload + "." + signature(payload);

        QrTokenVerifier.VerifiedQr verified = verifier.verify(token);

        assertEquals(tenant, verified.tenantId());
        assertEquals(connector, verified.connectorId());
    }

    @Test
    void rejectsTamperedToken() throws Exception {
        String payload = "sc1." + UUID.randomUUID() + "." + UUID.randomUUID();
        String token = payload + "." + signature(payload);
        assertThrows(IllegalArgumentException.class, () -> verifier.verify(token.replace("sc1", "sc2")));
    }

    private String signature(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }
}
