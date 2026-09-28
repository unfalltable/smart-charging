package io.smartcharge.platform.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PlatformAuthorityTest {
    @Test
    void recognizesExplicitPlatformAuthority() {
        PlatformAuthority authority = new PlatformAuthority();

        assertThat(authority.isPlatformAdministrator(authentication("platform-admin", true))).isTrue();
    }

    @Test
    void doesNotElevateAnotherTenantAdminToPlatformAdministrator() {
        PlatformAuthority authority = new PlatformAuthority();

        assertThat(authority.isPlatformAdministrator(authentication("tenant-admin", false))).isFalse();
    }

    @Test
    void neverElevatesAnIdentityByUsername() {
        PlatformAuthority authority = new PlatformAuthority();

        assertThat(authority.isPlatformAdministrator(authentication("platform-admin", false))).isFalse();
    }

    static JwtAuthenticationToken authentication(String username, boolean platformAdministrator) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("verified-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", username)
                .claim("tenant_ids", List.of("00000000-0000-0000-0000-000000000001"))
                .build();
        var authorities = platformAdministrator
                ? List.of(new SimpleGrantedAuthority("SCOPE_platform_admin"))
                : List.<SimpleGrantedAuthority>of();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
