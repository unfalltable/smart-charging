package io.smartcharge.platform.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class AdminAccountStatusFilterTest {
    private final UUID userId = UUID.randomUUID();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void acceptsAnActiveAccountWithTheCurrentAuthenticationVersion() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Boolean>>any(), eq(userId)))
                .thenReturn(List.of(true));
        SecurityContextHolder.getContext().setAuthentication(adminToken(userId, 7));
        AtomicBoolean invoked = new AtomicBoolean();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AdminAccountStatusFilter(jdbc).doFilter(
                new MockHttpServletRequest("GET", "/api/v1/platform/tenants"), response,
                (request, chainResponse) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(invoked).isTrue();
    }

    @Test
    void rejectsARevokedOrDisabledAdminSession() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Boolean>>any(), eq(userId)))
                .thenReturn(List.of(false));
        SecurityContextHolder.getContext().setAuthentication(adminToken(userId, 7));
        MockHttpServletResponse response = new MockHttpServletResponse();

        new AdminAccountStatusFilter(jdbc).doFilter(
                new MockHttpServletRequest("GET", "/api/v1/platform/tenants"), response,
                (request, chainResponse) -> { });

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录状态已失效");
    }

    @Test
    void leavesMiniProgramTokensToTheirOwnAuthenticationFlow() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        Jwt jwt = Jwt.withTokenValue("customer").header("alg", "none").subject("customer")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
        AtomicBoolean invoked = new AtomicBoolean();

        new AdminAccountStatusFilter(jdbc).doFilter(
                new MockHttpServletRequest("GET", "/api/v1/customer/orders"),
                new MockHttpServletResponse(), (request, response) -> invoked.set(true));

        assertThat(invoked).isTrue();
        verifyNoInteractions(jdbc);
    }

    private JwtAuthenticationToken adminToken(UUID id, long version) {
        Jwt jwt = Jwt.withTokenValue("admin").header("alg", "none").subject("admin-subject")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .claim("admin_user_id", id.toString()).claim("auth_version", version).build();
        return new JwtAuthenticationToken(jwt);
    }
}
