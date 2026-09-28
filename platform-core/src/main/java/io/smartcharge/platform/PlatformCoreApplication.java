package io.smartcharge.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import io.smartcharge.platform.identity.IdentityAdminProperties;
import io.smartcharge.platform.identity.LocalAdminProperties;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({IdentityAdminProperties.class, LocalAdminProperties.class})
public class PlatformCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformCoreApplication.class, args);
    }
}
