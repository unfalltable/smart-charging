package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/miniapp")
final class MiniappAuthController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final List<MiniappIdentityProvider> identityProviders;
    private final TokenService tokens;

    MiniappAuthController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                          List<MiniappIdentityProvider> identityProviders, TokenService tokens) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.identityProviders = List.copyOf(identityProviders);
        this.tokens = tokens;
    }

    @PostMapping("/login")
    TokenService.Session login(@Valid @RequestBody LoginRequest request) {
        UUID tenantId = jdbc.query("select id from tenant where code=? and status='ACTIVE'",
                (result, row) -> result.getObject(1, UUID.class), request.tenantCode()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Tenant is unavailable"));
        MiniappIdentityProvider provider = identityProviders.stream()
                .filter(candidate -> candidate.supports(request.provider(), request.tenantCode())).findFirst()
                .orElseThrow(() -> new DomainException("Mini-program identity provider is not configured"));
        MiniappIdentityProvider.ExternalIdentity identity = provider.exchange(request.code());
        return tenantJdbc.readWriteAs(tenantId, () -> {
            ExistingCustomer existing = jdbc.query("""
                    select ci.customer_id, c.status from customer_identity ci
                      join customer c on c.tenant_id=ci.tenant_id and c.id=ci.customer_id
                     where ci.tenant_id=? and ci.provider=? and ci.provider_subject=?
                    """, (result, row) -> new ExistingCustomer(
                    result.getObject("customer_id", UUID.class), result.getString("status")), tenantId,
                    request.provider(), identity.providerSubject()).stream().findFirst().orElse(null);
            if (existing != null && !"ACTIVE".equals(existing.status())) {
                throw new AuthenticationFailureException("Customer account is not active");
            }
            UUID customerId = existing == null ? null : existing.customerId();
            if (customerId == null) {
                customerId = UUID.randomUUID();
                jdbc.update("""
                        insert into customer (id, tenant_id, status, display_name)
                        values (?, ?, 'ACTIVE', '小程序用户')
                        """, customerId, tenantId);
                jdbc.update("""
                        insert into customer_identity
                            (id, tenant_id, customer_id, provider, provider_subject, union_subject)
                        values (?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), tenantId, customerId, request.provider(),
                        identity.providerSubject(), identity.unionSubject());
                jdbc.update("""
                        insert into wallet_account (id, tenant_id, customer_id, currency, status)
                        values (?, ?, ?, 'CNY', 'ACTIVE')
                        """, UUID.randomUUID(), tenantId, customerId);
            }
            return tokens.issue(tenantId, customerId);
        });
    }

    @PostMapping("/refresh")
    TokenService.Session refresh(@Valid @RequestBody RefreshRequest request) {
        return tenantJdbc.readWriteAs(request.tenantId(), () -> tokens.rotate(request.tenantId(), request.refreshToken()));
    }

    @PostMapping("/logout")
    void logout(@Valid @RequestBody RefreshRequest request) {
        tenantJdbc.readWriteAs(request.tenantId(), () -> {
            tokens.revoke(request.tenantId(), request.refreshToken());
            return null;
        });
    }

    record LoginRequest(@NotBlank @Pattern(regexp = "WECHAT|ALIPAY") String provider,
                        @NotBlank @jakarta.validation.constraints.Size(max = 128) String code,
                        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String tenantCode) { }
    record RefreshRequest(@NotNull UUID tenantId, @NotBlank @jakarta.validation.constraints.Size(min = 40, max = 100) String refreshToken) { }
    record ExistingCustomer(UUID customerId, String status) { }
}
