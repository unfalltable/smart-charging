package io.smartcharge.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void acceptsOnlyScopesSignedIntoThePlatformToken() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-id")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("scope", "platform_admin admin")
                .claim("realm_access", java.util.Map.of("roles", java.util.List.of("operator")))
                .build();

        AbstractAuthenticationToken authentication = new SecurityConfiguration()
                .jwtAuthenticationConverter().convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("subject-id");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .containsExactlyInAnyOrder("SCOPE_platform_admin", "SCOPE_admin")
                .doesNotContain("SCOPE_operator", "SCOPE_internal");
    }
}
