package io.smartcharge.platform.customer;

import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/account")
final class CustomerAccountController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final CurrentCustomer currentCustomer;

    CustomerAccountController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, CurrentCustomer currentCustomer) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping("/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void close() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        tenantJdbc.readWrite(() -> {
            Integer locked = jdbc.query("""
                    select 1 from customer where tenant_id=? and id=? and status='ACTIVE' for update
                    """, (result, row) -> result.getInt(1), tenantId, customerId).stream().findFirst()
                    .orElseThrow(() -> new DomainException("Customer account is not active"));
            if (locked != 1) throw new DomainException("Customer account is not active");
            boolean activeOrder = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from charging_order where tenant_id=? and customer_id=?
                     and status in ('CREATED','START_PENDING','CHARGING','STOP_PENDING'))
                    """, Boolean.class, tenantId, customerId));
            if (activeOrder) throw new DomainException("Stop the active charging order before closing the account");
            boolean unpaidOrder = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from charging_order where tenant_id=? and customer_id=?
                     and payable_amount_minor>paid_amount_minor and status not in ('CANCELLED'))
                    """, Boolean.class, tenantId, customerId));
            if (unpaidOrder) throw new DomainException("Pay all outstanding charging orders before closing the account");
            boolean walletFunds = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from wallet_account where tenant_id=? and customer_id=?
                     and (balance_minor<>0 or frozen_minor<>0))
                    """, Boolean.class, tenantId, customerId));
            if (walletFunds) throw new DomainException("Withdraw or settle the wallet balance before closing the account");

            jdbc.update("update auth_refresh_token set revoked_at=coalesce(revoked_at,now()) where tenant_id=? and customer_id=?",
                    tenantId, customerId);
            jdbc.update("delete from customer_identity where tenant_id=? and customer_id=?", tenantId, customerId);
            jdbc.update("""
                    update customer set status='CLOSED', display_name='已注销用户', mobile_ciphertext=null,
                           updated_at=now(), version=version+1 where tenant_id=? and id=?
                    """, tenantId, customerId);
            return null;
        });
    }
}
