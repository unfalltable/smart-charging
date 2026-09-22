package io.smartcharge.platform.finance;

import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/finance")
final class CustomerFinanceController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final CurrentCustomer currentCustomer;

    CustomerFinanceController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, CurrentCustomer currentCustomer) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.currentCustomer = currentCustomer;
    }

    @GetMapping("/wallet")
    WalletView wallet() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> {
            jdbc.update("""
                    insert into wallet_account (id, tenant_id, customer_id, currency, status)
                    values (?, ?, ?, 'CNY', 'ACTIVE') on conflict (tenant_id, customer_id, currency) do nothing
                    """, UUID.randomUUID(), tenantId, customerId);
            return jdbc.query("""
                    select id, currency, balance_minor, frozen_minor, status, version
                      from wallet_account where tenant_id=? and customer_id=? and currency='CNY'
                    """, (result, row) -> new WalletView(
                    result.getObject("id", UUID.class), result.getString("currency"),
                    result.getLong("balance_minor"), result.getLong("frozen_minor"),
                    result.getString("status"), result.getLong("version")), tenantId, customerId).getFirst();
        });
    }

    @GetMapping("/payments")
    List<CustomerPaymentView> payments() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select p.id, p.order_id, o.order_no, p.channel, p.amount_minor, p.currency,
                       p.status, p.created_at, p.completed_at
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and o.customer_id=? order by p.created_at desc limit 100
                """, (result, row) -> new CustomerPaymentView(
                    result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                    result.getString("order_no"), result.getString("channel"), result.getLong("amount_minor"),
                    result.getString("currency"), result.getString("status"),
                    result.getTimestamp("created_at").toInstant(), timestamp(result.getTimestamp("completed_at"))),
                tenantId, customerId));
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceView requestInvoice(@Valid @RequestBody InvoiceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> {
            PaidOrder order = jdbc.query("""
                    select payable_amount_minor, paid_amount_minor from charging_order
                     where tenant_id=? and customer_id=? and id=? and status='COMPLETED'
                    """, (result, row) -> new PaidOrder(
                    result.getLong("payable_amount_minor"), result.getLong("paid_amount_minor")),
                    tenantId, customerId, request.orderId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Completed order does not exist"));
            if (order.paidAmountMinor() < order.payableAmountMinor() || order.payableAmountMinor() <= 0) {
                throw new DomainException("Only a fully paid order can be invoiced");
            }
            UUID id = UUID.randomUUID();
            int inserted = jdbc.update("""
                    insert into invoice_request
                        (id, tenant_id, customer_id, order_id, title, tax_number, email, amount_minor, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'SUBMITTED')
                    on conflict (tenant_id, order_id) do nothing
                    """, id, tenantId, customerId, request.orderId(), request.title(), request.taxNumber(),
                    request.email(), order.payableAmountMinor());
            if (inserted != 1) throw new DomainException("An invoice has already been requested for this order");
            return new InvoiceView(id, request.orderId(), request.title(), request.taxNumber(), request.email(),
                    order.payableAmountMinor(), "SUBMITTED", null, Instant.now());
        });
    }

    @GetMapping("/invoices")
    List<InvoiceView> invoices() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, order_id, title, tax_number, email, amount_minor, status, invoice_url, created_at
                  from invoice_request where tenant_id=? and customer_id=? order by created_at desc
                """, (result, row) -> new InvoiceView(
                    result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                    result.getString("title"), result.getString("tax_number"), result.getString("email"),
                    result.getLong("amount_minor"), result.getString("status"), result.getString("invoice_url"),
                    result.getTimestamp("created_at").toInstant()), tenantId, customerId));
    }

    private static Instant timestamp(java.sql.Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    record InvoiceRequest(@NotNull UUID orderId, @NotBlank @Size(max = 200) String title,
                          @Size(max = 64) String taxNumber, @NotBlank @Email @Size(max = 254) String email) { }
    record PaidOrder(long payableAmountMinor, long paidAmountMinor) { }
    record WalletView(UUID id, String currency, long balanceMinor, long frozenMinor, String status, long version) { }
    record CustomerPaymentView(UUID id, UUID orderId, String orderNo, String channel, long amountMinor,
                               String currency, String status, Instant createdAt, Instant completedAt) { }
    record InvoiceView(UUID id, UUID orderId, String title, String taxNumber, String email,
                       long amountMinor, String status, String invoiceUrl, Instant createdAt) { }
}
