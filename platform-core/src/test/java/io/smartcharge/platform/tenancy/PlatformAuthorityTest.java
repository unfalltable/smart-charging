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
    void recognizesTheConfiguredBundledAdministratorWithoutARealmRole() {
        PlatformAuthority authority = new PlatformAuthority("bundled", "platform-admin");

        assertThat(authority.isPlatformAdministrator(authentication("platform-admin", false))).isTrue();
    }

    @Test
    void doesNotElevateAnotherTenantAdminToPlatformAdministrator() {
        PlatformAuthority authority = new PlatformAuthority("bundled", "platform-admin");

        assertThat(authority.isPlatformAdministrator(authentication("tenant-admin", true))).isFalse();
    }

    @Test
    void neverElevatesAnExternalIdentityByUsername() {
        PlatformAuthority authority = new PlatformAuthority("external", "platform-admin");

        assertThat(authority.isPlatformAdministrator(authentication("platform-admin", true))).isFalse();
    }

    static JwtAuthenticationToken authentication(String username, boolean admin) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("verified-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", username)
                .claim("tenant_ids", List.of("00000000-0000-0000-0000-000000000001"))
                .build();
        var authorities = admin
                ? List.of(new SimpleGrantedAuthority("SCOPE_admin"))
                : List.<SimpleGrantedAuthority>of();
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
