package io.smartcharge.platform.gateway.security;

import io.smartcharge.platform.gateway.GatewayProperties;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "gateway.credentials", name = "provider", havingValue = "configuration", matchIfMissing = true)
final class ConfigurationCredentialProvider implements DeviceCredentialProvider {
    private final GatewayProperties properties;

    ConfigurationCredentialProvider(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public byte[] secretFor(String deviceCode) {
        String secret = properties.deviceSecrets().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(deviceCode))
                .map(java.util.Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (secret == null || secret.length() < 32) {
            throw new DeviceAuthenticationException("Unknown device or invalid credential");
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
