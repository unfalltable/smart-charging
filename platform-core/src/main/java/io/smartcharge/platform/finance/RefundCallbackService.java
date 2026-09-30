package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
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
    private final ProfitSharingReturnPlanner profitSharingReturns;

    RefundCallbackService(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                          PaymentGatewayRegistry gateways, AuditService audit,
                          ProfitSharingReturnPlanner profitSharingReturns) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.audit = audit;
        this.profitSharingReturns = profitSharingReturns;
    }

    void process(String channel, String tenantCode, String merchantId,
                 Map<String, String> headers, String body) {
        UUID tenantId = resolveTenant(tenantCode);
        UUID merchantChannelId = resolveMerchantChannel(tenantId, channel, merchantId);
        PaymentGateway.VerifiedRefundCallback callback = gateways.required(channel)
                .verifyRefundCallback(tenantId, merchantChannelId, headers, body);
        tenantJdbc.readWriteAs(tenantId, () -> {
            RefundRecord refund = jdbc.query("""
                    select r.id, r.payment_id, p.order_id, p.merchant_channel_id,
                           p.channel, p.currency, p.provider_transaction_no, r.amount_minor, r.status,
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
                    result.getObject("merchant_channel_id", UUID.class), result.getString("currency"),
                    result.getString("provider_transaction_no"),
                    result.getLong("amount_minor"), result.getString("status"),
                    result.getString("recipient")),
                    tenantId, callback.merchantRefundNo()).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown merchant refund"));
            if (!channel.equals(refund.channel())) throw new DomainException("Refund callback channel mismatch");
            if (!merchantChannelId.equals(refund.merchantChannelId())) {
                throw new DomainException("Refund callback merchant route mismatch");
            }
            if (!java.util.Objects.equals(refund.providerTransactionNo(), callback.providerTransactionNo())) {
                throw new DomainException("Refund callback original transaction mismatch");
            }
            int inserted = jdbc.update("""
                    insert into payment_webhook
                        (id, tenant_id, payment_id, channel, provider_event_id, signature_valid, payload, processed_at)
                    values (?, ?, ?, ?, ?, true, cast(? as jsonb), now()) on conflict do nothing
                    """, UUID.randomUUID(), tenantId, refund.paymentId(), channel,
                    callback.providerEventId(), callback.rawPayload());
            if (inserted == 0) return null;
            transition(tenantId, refund, new PaymentGateway.GatewayRefundStatus(
                    callback.providerRefundNo(), callback.amountMinor(), callback.state(), callback.completedAt()), "CALLBACK");
            return null;
        });
    }

    void applyProviderStatus(UUID tenantId, UUID refundId, PaymentGateway.GatewayRefundStatus status) {
        tenantJdbc.readWriteAs(tenantId, () -> {
            transition(tenantId, lockById(tenantId, refundId), status, "PROVIDER_QUERY");
            return null;
        });
    }

    void recoverMissingRefund(UUID tenantId, UUID refundId) {
        RefundSubmission original = tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                select r.id, r.merchant_refund_no, r.amount_minor, r.reason, r.status,
                       p.merchant_channel_id, p.channel, p.provider_transaction_no, p.amount_minor payment_amount, p.currency
                  from refund_transaction r
                  join payment_transaction p on p.tenant_id=r.tenant_id and p.id=r.payment_id
                 where r.tenant_id=? and r.id=? for update of r
                """, (result, row) -> new RefundSubmission(result.getObject("id", UUID.class),
                result.getString("merchant_refund_no"), result.getLong("amount_minor"), result.getString("reason"),
                result.getString("status"), result.getObject("merchant_channel_id", UUID.class),
                result.getString("channel"), result.getString("provider_transaction_no"),
                result.getLong("payment_amount"), result.getString("currency")), tenantId, refundId).getFirst());
        if (!java.util.Set.of("CREATED", "PROCESSING").contains(original.status())) return;
        try {
            PaymentGateway.GatewayRefund result = gateways.required(original.channel()).createRefund(
                    new PaymentGateway.GatewayRefundRequest(tenantId, original.merchantChannelId(), original.id(),
                            original.merchantRefundNo(), original.providerTransactionNo(), original.amountMinor(),
                            original.paymentAmount(), original.currency(), original.reason()));
            tenantJdbc.readWriteAs(tenantId, () -> jdbc.update("""
                    update refund_transaction set provider_refund_no=coalesce(?, provider_refund_no),
                                                  status='PROCESSING', updated_at=now()
                     where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                    """, result.providerRefundNo(), tenantId, refundId));
            if (result.completed()) {
                applyProviderStatus(tenantId, refundId, new PaymentGateway.GatewayRefundStatus(result.providerRefundNo(),
                        original.amountMinor(), PaymentGateway.ProviderState.SUCCEEDED, result.completedAt()));
            }
        } catch (ProviderRequestRejectedException rejected) {
            tenantJdbc.readWriteAs(tenantId, () -> {
                audit.record("REFUND_PROVIDER_REJECTED", "refund_transaction", refundId, null,
                        Map.of("providerCode", rejected.code(), "source", "RECOVERY"));
                return null;
            });
            throw rejected;
        }
    }

    private void transition(UUID tenantId, RefundRecord refund,
                            PaymentGateway.GatewayRefundStatus provider, String source) {
        if ("SUCCEEDED".equals(refund.status())) return;
        if (provider.state() == PaymentGateway.ProviderState.PENDING) {
            jdbc.update("""
                    update refund_transaction set updated_at=now()
                     where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                    """, tenantId, refund.id());
            return;
        }
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
        if (provider.providerRefundNo() == null || provider.providerRefundNo().isBlank() || provider.completedAt() == null) {
            throw new DomainException("Provider refund success evidence is incomplete");
        }
        int changed = jdbc.update("""
                update refund_transaction set status='SUCCEEDED', provider_refund_no=?, completed_at=?, updated_at=now()
                 where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                """, provider.providerRefundNo(), JdbcTimes.timestamp(provider.completedAt()), tenantId, refund.id());
        if (changed != 1) throw new DomainException("Refund cannot enter succeeded state");
        int orderChanged = jdbc.update("""
                update charging_order set paid_amount_minor=paid_amount_minor-?, updated_at=now(), version=version+1
                 where tenant_id=? and id=? and paid_amount_minor>=?
                """, refund.amountMinor(), tenantId, refund.orderId(), refund.amountMinor());
        if (orderChanged != 1) throw new DomainException("Refund would make the paid amount negative");
        reverseLedger(tenantId, refund, provider.completedAt());
        profitSharingReturns.onRefundSucceeded(tenantId, refund.paymentId(), refund.id());
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
                select r.id, r.payment_id, p.order_id, p.merchant_channel_id,
                       p.channel, p.currency, p.provider_transaction_no, r.amount_minor, r.status,
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
                result.getObject("merchant_channel_id", UUID.class), result.getString("currency"),
                result.getString("provider_transaction_no"),
                result.getLong("amount_minor"), result.getString("status"),
                result.getString("recipient")), tenantId, refundId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown refund"));
    }

    private void reverseLedger(UUID tenantId, RefundRecord refund, java.time.Instant completedAt) {
        UUID cash = account(tenantId, "CASH:" + refund.channel(), refund.currency());
        UUID revenue = account(tenantId, "CHARGING_REVENUE", refund.currency());
        UUID transactionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into ledger_transaction
                    (id, tenant_id, reference_type, reference_id, description, occurred_at)
                values (?, ?, 'REFUND', ?, ?, ?) on conflict do nothing
                """, transactionId, tenantId, refund.id(), "Refund callback " + refund.id(), JdbcTimes.timestamp(completedAt));
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
        return jdbc.query("select id from tenant where code=?",
                (result, row) -> result.getObject(1, UUID.class), tenantCode).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant callback route"));
    }

    private UUID resolveMerchantChannel(UUID tenantId, String channel, String merchantId) {
        if (merchantId == null || !merchantId.matches("[A-Za-z0-9_-]{3,128}")) {
            throw new IllegalArgumentException("Invalid merchant callback route");
        }
        return tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                select id from merchant_channel
                 where tenant_id=? and channel=? and merchant_id=?
                """, (result, row) -> result.getObject(1, UUID.class),
                tenantId, channel, merchantId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown merchant callback route")));
    }

    private record RefundRecord(UUID id, UUID paymentId, UUID orderId, String channel,
                                UUID merchantChannelId,
                                String currency, String providerTransactionNo, long amountMinor, String status, String recipient) { }
    private record RefundSubmission(UUID id, String merchantRefundNo, long amountMinor, String reason, String status,
                                    UUID merchantChannelId, String channel, String providerTransactionNo,
                                    long paymentAmount, String currency) { }
}
