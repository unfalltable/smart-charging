package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
final class RefundCallbackService {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PaymentGatewayRegistry gateways;
    private final AuditService audit;

    RefundCallbackService(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                          PaymentGatewayRegistry gateways, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.audit = audit;
    }

    void process(String channel, String tenantCode, Map<String, String> headers, String body) {
        UUID tenantId = resolveTenant(tenantCode);
        PaymentGateway.VerifiedRefundCallback callback = gateways.required(channel)
                .verifyRefundCallback(tenantId, headers, body);
        tenantJdbc.readWriteAs(tenantId, () -> {
            RefundRecord refund = jdbc.query("""
                    select r.id, r.payment_id, p.order_id, p.channel, p.currency, r.amount_minor, r.status,
                           (select ci.provider_subject from charging_order o
                             join customer_identity ci on ci.tenant_id=o.tenant_id and ci.customer_id=o.customer_id
                            where o.tenant_id=p.tenant_id and o.id=p.order_id and ci.provider=p.channel
                            order by ci.created_at limit 1) recipient
                      from refund_transaction r
                      join payment_transaction p on p.tenant_id=r.tenant_id and p.id=r.payment_id
                     where r.tenant_id=? and r.merchant_refund_no=? for update of r
                    """, (result, row) -> new RefundRecord(
                    result.getObject("id", UUID.class), result.getObject("payment_id", UUID.class),
                    result.getObject("order_id", UUID.class), result.getString("channel"),
                    result.getString("currency"), result.getLong("amount_minor"), result.getString("status"),
                    result.getString("recipient")),
                    tenantId, callback.merchantRefundNo()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown merchant refund"));
            if (!channel.equals(refund.channel())) throw new DomainException("Refund callback channel mismatch");
            int inserted = jdbc.update("""
                    insert into payment_webhook
                        (id, tenant_id, payment_id, channel, provider_event_id, signature_valid, payload, processed_at)
                    values (?, ?, ?, ?, ?, true, cast(? as jsonb), now()) on conflict do nothing
                    """, UUID.randomUUID(), tenantId, refund.paymentId(), channel,
                    callback.providerEventId(), callback.rawPayload());
            if (inserted == 0) return null;
            transition(tenantId, refund, new PaymentGateway.GatewayRefundStatus(
                    callback.providerRefundNo(), callback.amountMinor(), callback.succeeded()
                    ? PaymentGateway.ProviderState.SUCCEEDED : PaymentGateway.ProviderState.FAILED), "CALLBACK");
            return null;
        });
    }

    void applyProviderStatus(UUID tenantId, UUID refundId, PaymentGateway.GatewayRefundStatus status) {
        tenantJdbc.readWriteAs(tenantId, () -> {
            transition(tenantId, lockById(tenantId, refundId), status, "PROVIDER_QUERY");
            return null;
        });
    }

    private void transition(UUID tenantId, RefundRecord refund,
                            PaymentGateway.GatewayRefundStatus provider, String source) {
        if ("SUCCEEDED".equals(refund.status()) || provider.state() == PaymentGateway.ProviderState.PENDING) return;
        if (provider.amountMinor() != refund.amountMinor()) {
            throw new DomainException("Provider refund amount does not match");
        }
        if (provider.state() == PaymentGateway.ProviderState.FAILED) {
            int changed = jdbc.update("""
                    update refund_transaction set status='FAILED', provider_refund_no=coalesce(?, provider_refund_no),
                                                  updated_at=now()
                     where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                    """, provider.providerRefundNo(), tenantId, refund.id());
            if (changed == 1) {
                audit.record("REFUND_FAILED", "refund_transaction", refund.id(), null, Map.of("source", source));
            }
            return;
        }
        int changed = jdbc.update("""
                update refund_transaction set status='SUCCEEDED', provider_refund_no=?, completed_at=now(), updated_at=now()
                 where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                """, provider.providerRefundNo(), tenantId, refund.id());
        if (changed != 1) throw new DomainException("Refund cannot enter succeeded state");
        int orderChanged = jdbc.update("""
                update charging_order set paid_amount_minor=paid_amount_minor-?, updated_at=now(), version=version+1
                 where tenant_id=? and id=? and paid_amount_minor>=?
                """, refund.amountMinor(), tenantId, refund.orderId(), refund.amountMinor());
        if (orderChanged != 1) throw new DomainException("Refund would make the paid amount negative");
        reverseLedger(tenantId, refund);
        jdbc.update("""
                insert into notification_outbox (id, tenant_id, channel, template_code, recipient, payload, status)
                values (?, ?, ?, 'REFUND_SUCCEEDED', ?,
                        jsonb_build_object('refundId', ?, 'amountMinor', ?), 'PENDING')
                """, UUID.randomUUID(), tenantId, refund.channel(), refund.recipient(),
                refund.id().toString(), refund.amountMinor());
        audit.record("REFUND_SUCCEEDED", "refund_transaction", refund.id(), null,
                Map.of("source", source, "amountMinor", refund.amountMinor()));
    }

    private RefundRecord lockById(UUID tenantId, UUID refundId) {
        return jdbc.query("""
                select r.id, r.payment_id, p.order_id, p.channel, p.currency, r.amount_minor, r.status,
                       (select ci.provider_subject from charging_order o
                         join customer_identity ci on ci.tenant_id=o.tenant_id and ci.customer_id=o.customer_id
                        where o.tenant_id=p.tenant_id and o.id=p.order_id and ci.provider=p.channel
                        order by ci.created_at limit 1) recipient
                  from refund_transaction r
                  join payment_transaction p on p.tenant_id=r.tenant_id and p.id=r.payment_id
                 where r.tenant_id=? and r.id=? for update of r
                """, (result, row) -> new RefundRecord(
                result.getObject("id", UUID.class), result.getObject("payment_id", UUID.class),
                result.getObject("order_id", UUID.class), result.getString("channel"),
                result.getString("currency"), result.getLong("amount_minor"), result.getString("status"),
                result.getString("recipient")), tenantId, refundId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown refund"));
    }

    private void reverseLedger(UUID tenantId, RefundRecord refund) {
        UUID cash = account(tenantId, "CASH:" + refund.channel(), refund.currency());
        UUID revenue = account(tenantId, "CHARGING_REVENUE", refund.currency());
        UUID transactionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into ledger_transaction
                    (id, tenant_id, reference_type, reference_id, description, occurred_at)
                values (?, ?, 'REFUND', ?, ?, now()) on conflict do nothing
                """, transactionId, tenantId, refund.id(), "Refund callback " + refund.id());
        if (inserted == 0) return;
        jdbc.update("""
                insert into ledger_entry (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency)
                values (?, ?, ?, ?, 'DEBIT', ?, ?), (?, ?, ?, ?, 'CREDIT', ?, ?)
                """, UUID.randomUUID(), tenantId, transactionId, revenue, refund.amountMinor(), refund.currency(),
                UUID.randomUUID(), tenantId, transactionId, cash, refund.amountMinor(), refund.currency());
    }

    private UUID account(UUID tenantId, String code, String currency) {
        return jdbc.queryForObject("select id from ledger_account where tenant_id=? and account_code=? and currency=?",
                UUID.class, tenantId, code, currency);
    }

    private UUID resolveTenant(String tenantCode) {
        if (tenantCode == null || !tenantCode.matches("[a-z0-9][a-z0-9-]{1,62}")) {
            throw new IllegalArgumentException("Invalid tenant callback route");
        }
        return jdbc.query("select id from tenant where code=? and status='ACTIVE'",
                (result, row) -> result.getObject(1, UUID.class), tenantCode).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant callback route"));
    }

    private record RefundRecord(UUID id, UUID paymentId, UUID orderId, String channel,
                                String currency, long amountMinor, String status, String recipient) { }
}
