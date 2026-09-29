package io.smartcharge.platform.identity;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
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
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String claim = jwt.getToken().getClaimAsString("customer_id");
            if (claim == null) throw new IllegalStateException("Token has no customer identity");
            UUID customerId = UUID.fromString(claim);
            boolean active = tenantJdbc.readWrite(() -> Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from customer where tenant_id=? and id=? and status='ACTIVE')
                    """, Boolean.class, io.smartcharge.platform.tenancy.TenantContext.requireTenantId(), customerId)));
            if (!active) throw new AccessDeniedException("Customer account is not active");
            return customerId;
        }
        throw new IllegalStateException("Authenticated customer identity is missing");
    }
}
