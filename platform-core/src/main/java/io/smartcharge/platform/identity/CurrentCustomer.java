package io.smartcharge.platform.identity;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
public final class CurrentCustomer {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;

    public CurrentCustomer(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
    }

    public UUID requireId() {
        return requireId(false);
    }

    public UUID requireIdForStop() {
        return requireId(true);
    }

    private UUID requireId(boolean stopOnly) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString("customer_id");
            UUID customerId;
            UUID familyId;
            try {
                customerId = UUID.fromString(claim);
                familyId = UUID.fromString(jwt.getToken().getClaimAsString("refresh_family_id"));
            } catch (RuntimeException malformed) {
                throw new AuthenticationFailureException("登录状态已失效，请重新登录");
            }
            boolean active = tenantJdbc.readWrite(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from customer c join tenant t on t.id=c.tenant_id
                                   where c.tenant_id=? and c.id=? %s
                                     and exists(select 1 from auth_refresh_token rt where rt.tenant_id=c.tenant_id
                                         and rt.customer_id=c.id and rt.family_id=?
                                         and rt.revoked_at is null and rt.expires_at>now()))
                    """.formatted(stopOnly ? "" : "and c.status='ACTIVE' and t.status='ACTIVE'"),
                    Boolean.class, io.smartcharge.platform.tenancy.TenantContext.requireTenantId(), customerId, familyId)));
            if (!active) throw new AuthenticationFailureException("登录状态已撤销或账号已停用，请重新登录");
            return customerId;
        }
        throw new AuthenticationFailureException("请先登录");
    }
}
