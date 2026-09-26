package io.smartcharge.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigurationTest {
    @Test
    void combinesStandardScopesAndKeycloakRealmRoles() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-id")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("scope", "profile")
                .claim("preferred_username", "platform-admin")
                .claim("realm_access", Map.of("roles", List.of("admin", "operator", "invalid role")))
                .build();

        Converter<Jwt, AbstractAuthenticationToken> converter =
                new SecurityConfiguration().jwtAuthenticationConverter();
        AbstractAuthenticationToken authentication = converter.convert(jwt);

        assertThat(authentication).isNotNull();
        assertThat(authentication.getName()).isEqualTo("subject-id");
        assertThat(authentication.getAuthorities()).extracting("authority")
                .contains("SCOPE_profile", "SCOPE_admin", "SCOPE_operator")
                .doesNotContain("SCOPE_invalid role");
    }
}
