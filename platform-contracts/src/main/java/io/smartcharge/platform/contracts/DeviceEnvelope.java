package io.smartcharge.platform.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeviceEnvelope(
        String protocolVersion,
        UUID messageId,
        String deviceCode,
        Instant occurredAt,
        String nonce,
        DeviceEventType eventType,
        String payload,
        String signature
) {
    public DeviceEnvelope {
        protocolVersion = requireText(protocolVersion, "protocolVersion");
        Objects.requireNonNull(messageId, "messageId is required");
        deviceCode = requireText(deviceCode, "deviceCode");
        if (!deviceCode.matches("[A-Za-z0-9._-]{1,96}")) {
            throw new IllegalArgumentException("deviceCode contains unsupported characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        nonce = requireText(nonce, "nonce");
        Objects.requireNonNull(eventType, "eventType is required");
        payload = requireText(payload, "payload");
        signature = requireText(signature, "signature");
    }

    public String signingText() {
        return String.join("\n", protocolVersion, messageId.toString(), deviceCode,
                occurredAt.toString(), nonce, eventType.name(), payload);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
