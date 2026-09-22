package io.smartcharge.platform.gateway.security;

import io.smartcharge.platform.contracts.DeviceCommand;
import io.smartcharge.platform.contracts.SignedDeviceCommand;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public final class DeviceCommandSigner {
    private final DeviceCredentialProvider credentials;
    private final Clock clock = Clock.systemUTC();

    public DeviceCommandSigner(DeviceCredentialProvider credentials) {
        this.credentials = credentials;
    }

    public SignedDeviceCommand sign(DeviceCommand command) {
        Instant issuedAt = Instant.now(clock);
        String nonce = UUID.randomUUID().toString();
        String signingText = SignedDeviceCommand.signingText(command, issuedAt, nonce);
        String signature = HexFormat.of().formatHex(DeviceMessageVerifier.sign(
                signingText, credentials.secretFor(command.deviceCode())));
        return new SignedDeviceCommand(command, issuedAt, nonce, signature);
    }
}
