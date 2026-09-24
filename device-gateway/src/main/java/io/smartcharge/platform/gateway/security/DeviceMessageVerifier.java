package io.smartcharge.platform.gateway.security;

import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.smartcharge.platform.gateway.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

@Component
public final class DeviceMessageVerifier {
    private final DeviceCredentialProvider credentials;
    private final NonceGuard nonceGuard;
    private final GatewayProperties properties;
    private final Clock clock;

    @Autowired
    public DeviceMessageVerifier(DeviceCredentialProvider credentials, NonceGuard nonceGuard,
                                 GatewayProperties properties) {
        this(credentials, nonceGuard, properties, Clock.systemUTC());
    }

    DeviceMessageVerifier(DeviceCredentialProvider credentials, NonceGuard nonceGuard,
                          GatewayProperties properties, Clock clock) {
        this.credentials = credentials;
        this.nonceGuard = nonceGuard;
        this.properties = properties;
        this.clock = clock;
    }

    public void verify(DeviceEnvelope envelope) {
        Duration age = Duration.between(envelope.occurredAt(), Instant.now(clock)).abs();
        if (age.compareTo(properties.allowedClockSkew()) > 0) {
            throw new DeviceAuthenticationException("Message timestamp is outside the allowed window");
        }
        byte[] expected = sign(envelope.signingText(), credentials.secretFor(envelope.deviceCode()));
        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(envelope.signature());
        } catch (IllegalArgumentException malformedSignature) {
            throw new DeviceAuthenticationException("Invalid signature");
        }
        if (!MessageDigest.isEqual(expected, supplied)) {
            throw new DeviceAuthenticationException("Invalid signature");
        }
        if (!nonceGuard.claim(envelope.deviceCode(), envelope.nonce())) {
            throw new DeviceAuthenticationException("Replayed message");
        }
    }

    static byte[] sign(String message, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to calculate message signature", exception);
        }
    }
}
