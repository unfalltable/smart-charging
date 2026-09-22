package io.smartcharge.platform.gateway;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway")
public record GatewayProperties(
        int port,
        int maxFrameLength,
        Duration allowedClockSkew,
        Duration nonceTtl,
        Map<String, String> deviceSecrets,
        Tls tls
) {
    public GatewayProperties {
        if (port < 1 || port > 65535) throw new IllegalArgumentException("gateway.port is invalid");
        if (maxFrameLength < 256) throw new IllegalArgumentException("gateway.max-frame-length is too small");
        deviceSecrets = Map.copyOf(deviceSecrets == null ? Map.of() : deviceSecrets);
    }

    public record Tls(boolean enabled, String certificateChain, String privateKey, String trustCertificates) { }
}
