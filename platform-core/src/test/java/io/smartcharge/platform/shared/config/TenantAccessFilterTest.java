package io.smartcharge.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class TenantAccessFilterTest {
    private final UUID tenantId = UUID.randomUUID();

    @AfterEach
    void clearContexts() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void configuredPlatformAdministratorUsesTheActiveDatabaseTenantWithoutAMembership() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        when(jdbc.queryForObject("select exists(select 1 from tenant where id=? and status='ACTIVE')",
                Boolean.class, tenantId)).thenReturn(true);
        TenantAccessFilter filter = new TenantAccessFilter(jdbc, tenantJdbc,
                new PlatformAuthority("bundled", "platform-admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication("platform-admin"));
        TenantContext.set(tenantId);
        AtomicBoolean invoked = new AtomicBoolean();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/operations/dashboard"),
                new MockHttpServletResponse(), (request, response) -> invoked.set(true));

        assertThat(invoked).isTrue();
        verify(tenantJdbc, never()).readWriteAs(eq(tenantId), org.mockito.ArgumentMatchers.any());
    }

    private JwtAuthenticationToken authentication(String username) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("verified-platform-owner")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("preferred_username", username)
                .claim("tenant_ids", List.of(UUID.randomUUID().toString()))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(), jwt.getSubject());
    }
}
