package io.smartcharge.platform.contracts;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeviceCommand(
        UUID commandId,
        UUID tenantId,
        String deviceCode,
        Integer connectorNo,
        DeviceCommandType commandType,
        Instant expiresAt,
        String payload
) {
    public DeviceCommand {
        Objects.requireNonNull(commandId, "commandId is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("deviceCode is required");
        }
        if (connectorNo != null && connectorNo < 1) {
            throw new IllegalArgumentException("connectorNo must be positive");
        }
        Objects.requireNonNull(commandType, "commandType is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        payload = payload == null ? "{}" : payload;
    }
}
