package io.smartcharge.platform.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
class AdminAuthenticationConfiguration {
    @Bean
    PasswordEncoder adminPasswordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
