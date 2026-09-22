package io.smartcharge.platform.contracts;

import java.time.Instant;
import java.util.Objects;

public record SignedDeviceCommand(DeviceCommand command, Instant issuedAt, String nonce, String signature) {
    public SignedDeviceCommand {
        Objects.requireNonNull(command, "command is required");
        Objects.requireNonNull(issuedAt, "issuedAt is required");
        if (nonce == null || nonce.isBlank()) throw new IllegalArgumentException("nonce is required");
        if (signature == null || signature.isBlank()) throw new IllegalArgumentException("signature is required");
    }

    public String signingText() {
        return signingText(command, issuedAt, nonce);
    }

    public static String signingText(DeviceCommand command, Instant issuedAt, String nonce) {
        return String.join("\n",
                command.commandId().toString(),
                command.tenantId().toString(),
                command.deviceCode(),
                command.connectorNo() == null ? "" : command.connectorNo().toString(),
                command.commandType().name(),
                command.expiresAt().toString(),
                command.payload(),
                issuedAt.toString(),
                nonce);
    }
}
