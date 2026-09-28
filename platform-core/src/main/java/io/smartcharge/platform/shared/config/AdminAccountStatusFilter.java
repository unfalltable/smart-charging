package io.smartcharge.platform.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class AdminAccountStatusFilter extends OncePerRequestFilter {
    private final JdbcTemplate jdbc;
    private final boolean databaseIdentity;

    public AdminAccountStatusFilter(JdbcTemplate jdbc,
                                    @Value("${charging.security.identity-provider-mode:external}") String mode) {
        this.jdbc = jdbc;
        this.databaseIdentity = "database".equalsIgnoreCase(mode);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!databaseIdentity || !(authentication instanceof JwtAuthenticationToken jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String rawUserId = jwt.getToken().getClaimAsString("admin_user_id");
        Number tokenVersion = jwt.getToken().getClaim("auth_version");
        if (rawUserId == null && tokenVersion == null) {
            chain.doFilter(request, response);
            return;
        }
        UUID userId;
        try {
            userId = UUID.fromString(rawUserId);
        } catch (RuntimeException invalid) {
            unauthorized(response);
            return;
        }
        if (tokenVersion == null) {
            unauthorized(response);
            return;
        }
        boolean active = Boolean.TRUE.equals(jdbc.query("""
                select auth_version from platform_user where id=? and status='ACTIVE'
                """, (result, row) -> result.getLong("auth_version") == tokenVersion.longValue(), userId)
                .stream().findFirst().orElse(false));
        if (!active) {
            unauthorized(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"登录状态已失效，请重新登录\",\"data\":null}");
    }
}
