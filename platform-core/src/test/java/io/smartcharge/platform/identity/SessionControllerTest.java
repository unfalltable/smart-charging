package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.identity.SessionController.TenantView;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SessionControllerTest {
    @Test
    void platformSessionUsesTheRealDatabaseTenantInsteadOfTheStaleTokenTenant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID databaseTenant = UUID.fromString("00000000-0000-0000-0000-000000000002");
        TenantView databaseView = new TenantView(databaseTenant, "gavin", "Gavin", List.of("PLATFORM_ADMIN"));
        when(jdbc.query(anyString(), ArgumentMatchers.<RowMapper<TenantView>>any()))
                .thenReturn(List.of(databaseView));
        SessionController controller = new SessionController(jdbc, mock(TenantJdbcExecutor.class),
                new PlatformAuthority("bundled", "platform-admin"));

        var session = controller.current(authentication());

        assertThat(session.platformAdministrator()).isTrue();
        assertThat(session.tenants()).containsExactly(databaseView);
        assertThat(session.tenants().getFirst().id().toString())
                .isNotEqualTo("00000000-0000-0000-0000-000000000001");
    }

    private JwtAuthenticationToken authentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("verified-subject")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", "platform-admin")
                .claim("tenant_ids", List.of("00000000-0000-0000-0000-000000000001"))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject());
    }
}
