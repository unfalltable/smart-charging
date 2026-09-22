package io.smartcharge.platform.gateway.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.smartcharge.platform.contracts.DeviceEventType;
import io.smartcharge.platform.gateway.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class DeviceMessageVerifierTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final byte[] SECRET = "a-strong-device-secret-at-least-32-bytes".getBytes(StandardCharsets.UTF_8);
    private final Set<String> nonces = ConcurrentHashMap.newKeySet();
    private final DeviceMessageVerifier verifier = new DeviceMessageVerifier(
            ignored -> SECRET,
            (device, nonce) -> nonces.add(device + ":" + nonce),
            new GatewayProperties(9000, 65536, Duration.ofMinutes(2), Duration.ofMinutes(10),
                    Map.of(), new GatewayProperties.Tls(false, "", "", "")),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsAuthenticMessageOnce() {
        DeviceEnvelope unsigned = envelope("00", NOW, "nonce-1");
        String signature = HexFormat.of().formatHex(DeviceMessageVerifier.sign(unsigned.signingText(), SECRET));
        DeviceEnvelope signed = envelope(signature, NOW, "nonce-1");

        assertDoesNotThrow(() -> verifier.verify(signed));
        assertThrows(DeviceAuthenticationException.class, () -> verifier.verify(signed));
    }

    @Test
    void rejectsStaleMessage() {
        DeviceEnvelope stale = envelope("00", NOW.minus(Duration.ofMinutes(3)), "nonce-2");
        assertThrows(DeviceAuthenticationException.class, () -> verifier.verify(stale));
    }

    private DeviceEnvelope envelope(String signature, Instant occurredAt, String nonce) {
        return new DeviceEnvelope("1.0", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "PILE-001", occurredAt, nonce, DeviceEventType.HEARTBEAT, "{}", signature);
    }
}
