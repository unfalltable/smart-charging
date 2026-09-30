package io.smartcharge.platform.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class CurrentCustomerTest {
    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void acceptsOnlyAnActiveCustomer() {
        UUID tenantId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        TenantJdbcExecutor tenantJdbc = mock(TenantJdbcExecutor.class);
        when(tenantJdbc.readWrite(any())).thenAnswer(invocation ->
                ((Supplier<?>) invocation.getArgument(0)).get());
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), eq(tenantId), eq(customerId), eq(familyId)))
                .thenReturn(true, false);
        TenantContext.set(tenantId);
        SecurityContextHolder.getContext().setAuthentication(customerToken(customerId, familyId));
        CurrentCustomer current = new CurrentCustomer(jdbc, tenantJdbc);

        assertThat(current.requireId()).isEqualTo(customerId);
        assertThatThrownBy(current::requireId).isInstanceOf(AuthenticationFailureException.class);
    }

    private JwtAuthenticationToken customerToken(UUID customerId, UUID familyId) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("customer:" + customerId)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .claim("customer_id", customerId.toString())
                .claim("refresh_family_id", familyId.toString())
                .claim("scope", "customer")
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("SCOPE_customer")));
    }
}
