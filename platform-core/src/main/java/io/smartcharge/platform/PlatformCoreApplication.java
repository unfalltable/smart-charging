package io.smartcharge.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PlatformCoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlatformCoreApplication.class, args);
    }
}
