package io.smartcharge.platform.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceEnvelopeTest {
    @Test
    void signingTextIsStable() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        DeviceEnvelope envelope = new DeviceEnvelope("1.0", id, "PILE-001",
                Instant.parse("2026-01-01T00:00:00Z"), "nonce-1",
                DeviceEventType.HEARTBEAT, "{}", "signature");

        assertEquals("1.0\n" + id + "\nPILE-001\n2026-01-01T00:00:00Z\nnonce-1\nHEARTBEAT\n{}",
                envelope.signingText());
    }
}
