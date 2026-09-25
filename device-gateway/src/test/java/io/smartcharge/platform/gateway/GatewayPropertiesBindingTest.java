package io.smartcharge.platform.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class GatewayPropertiesBindingTest {
    @Test
    void bindsDockerDeviceSecretFromSpringApplicationJson() {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("docker", Map.of(
                "SPRING_APPLICATION_JSON",
                "{\"gateway\":{\"port\":9000,\"max-frame-length\":65536,"
                        + "\"allowed-clock-skew\":\"2m\",\"nonce-ttl\":\"10m\","
                        + "\"device-secrets\":{\"PILE001\":\"a-strong-device-secret-at-least-32-bytes\"},"
                        + "\"tls\":{\"enabled\":false}}}"
        )));
        new SpringApplicationJsonEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication(GatewayPropertiesBindingTest.class));

        GatewayProperties properties = Binder.get(environment)
                .bind("gateway", Bindable.of(GatewayProperties.class))
                .orElseThrow(() -> new AssertionError("Gateway properties did not bind"));

        assertEquals("a-strong-device-secret-at-least-32-bytes",
                properties.deviceSecrets().get("PILE001"));
    }

    @Test
    void preservesDeviceCodeAsMapKey() {
        var source = new MapConfigurationPropertySource(Map.of(
                "gateway.port", "9000",
                "gateway.max-frame-length", "65536",
                "gateway.allowed-clock-skew", "2m",
                "gateway.nonce-ttl", "10m",
                "gateway.device-secrets.PILE001", "a-strong-device-secret-at-least-32-bytes",
                "gateway.tls.enabled", "false"
        ));

        GatewayProperties properties = new Binder(source)
                .bind("gateway", Bindable.of(GatewayProperties.class))
                .orElseThrow(() -> new AssertionError("Gateway properties did not bind"));

        assertEquals("a-strong-device-secret-at-least-32-bytes", properties.deviceSecrets().get("PILE001"));
    }
}
