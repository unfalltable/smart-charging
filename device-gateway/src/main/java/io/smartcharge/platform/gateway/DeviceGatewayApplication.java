package io.smartcharge.platform.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import io.smartcharge.platform.gateway.security.CredentialStoreProperties;

@SpringBootApplication
@EnableConfigurationProperties({GatewayProperties.class, CredentialStoreProperties.class})
public class DeviceGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeviceGatewayApplication.class, args);
    }
}
