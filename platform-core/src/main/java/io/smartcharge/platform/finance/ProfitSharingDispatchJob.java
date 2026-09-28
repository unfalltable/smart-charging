package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
final class ProfitSharingDispatchJob {
    private static final Logger LOG = LoggerFactory.getLogger(ProfitSharingDispatchJob.class);
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PaymentGatewayRegistry gateways;
    private final AuditService audit;

    ProfitSharingDispatchJob(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                             PaymentGatewayRegistry gateways, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${payments.profit-sharing-delay-ms:15000}")
    void dispatch() {
        List<UUID> tenants = jdbc.query("select id from tenant where status='ACTIVE' order by id",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) {
            for (int handled = 0; handled < 50; handled++) {
                SharingOrder order = claim(tenantId);
                if (order == null) break;
                send(tenantId, order);
            }
        }
    }

    private SharingOrder claim(UUID tenantId) {
        return tenantJdbc.readWriteAs(tenantId, () -> {
            SharingOrder order = jdbc.query("""
                    select s.id, s.merchant_channel_id, s.channel, s.out_order_no, s.provider_order_no,
                           p.provider_transaction_no
                      from payment_profit_sharing_order s
                      join payment_transaction p on p.tenant_id=s.tenant_id and p.id=s.payment_id
                     where s.tenant_id=? and p.status='SUCCEEDED'
                       and s.status in ('PLANNED','PROCESSING','FAILED')
                       and s.attempts<20 and s.next_attempt_at<=now()
                     order by s.next_attempt_at, s.created_at
                     for update of s skip locked limit 1
                    """, (result, row) -> new SharingOrder(
                    result.getObject("id", UUID.class),
                    result.getObject("merchant_channel_id", UUID.class), result.getString("channel"),
                    result.getString("out_order_no"), result.getString("provider_order_no"),
                    result.getString("provider_transaction_no")), tenantId).stream().findFirst().orElse(null);
            if (order == null) return null;
            jdbc.update("""
                    update payment_profit_sharing_order
                       set status='PROCESSING', attempts=attempts+1,
                           next_attempt_at=now()+interval '30 seconds', updated_at=now()
                     where tenant_id=? and id=?
                    """, tenantId, order.id());
            return order;
        });
    }

    private void send(UUID tenantId, SharingOrder order) {
        try {
            PaymentGateway gateway = gateways.required(order.channel());
            PaymentGateway.GatewayProfitSharing result = order.providerOrderNo() == null
                    ? gateway.createProfitSharing(new PaymentGateway.GatewayProfitSharingRequest(
                    tenantId, order.merchantChannelId(), order.providerTransactionNo(), order.outOrderNo(),
                    allocations(tenantId, order.id())))
                    : gateway.queryProfitSharing(tenantId, order.merchantChannelId(),
                    order.providerTransactionNo(), order.outOrderNo());
            apply(tenantId, order, result);
        } catch (RuntimeException failure) {
            tenantJdbc.readWriteAs(tenantId, () -> {
                jdbc.update("""
                        update payment_profit_sharing_order
                           set status='FAILED', last_error=?,
                               next_attempt_at=now()+make_interval(secs => least(1800,
                                   30 * power(2, least(attempts, 6))::integer)), updated_at=now()
                         where tenant_id=? and id=?
                        """, safeMessage(failure), tenantId, order.id());
                return null;
            });
            LOG.warn("Profit sharing dispatch failed: orderId={}, reason={}",
                    order.id(), failure.getClass().getSimpleName());
        }
    }

    private List<PaymentGateway.ProfitSharingAllocation> allocations(UUID tenantId, UUID orderId) {
        return tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                select receiver_account, receiver_name, amount_minor, owner_type
                  from payment_profit_sharing_detail
                 where tenant_id=? and sharing_order_id=? order by receiver_account
                """, (result, row) -> new PaymentGateway.ProfitSharingAllocation(
                result.getString("receiver_account"), result.getString("receiver_name"),
                result.getLong("amount_minor"),
                "PLATFORM".equals(result.getString("owner_type")) ? "平台服务费" : "合作方分润"),
                tenantId, orderId));
    }

    private void apply(UUID tenantId, SharingOrder order, PaymentGateway.GatewayProfitSharing result) {
        tenantJdbc.readWriteAs(tenantId, () -> {
            for (PaymentGateway.ProfitSharingResult receiver : result.receivers()) {
                jdbc.update("""
                        update payment_profit_sharing_detail
                           set status=?, fail_reason=?, updated_at=now()
                         where tenant_id=? and sharing_order_id=? and receiver_account=?
                        """, detailStatus(receiver.state()), receiver.failReason(), tenantId, order.id(),
                        receiver.account());
            }
            if (result.state() == PaymentGateway.ProviderState.FAILED) {
                jdbc.update("""
                        update payment_profit_sharing_detail
                           set status='FAILED', fail_reason=coalesce(fail_reason, 'Provider closed the sharing order'),
                               updated_at=now()
                         where tenant_id=? and sharing_order_id=? and status='PENDING'
                        """, tenantId, order.id());
            }
            postSuccessfulAllocations(tenantId, order.id());
            long pending = countDetails(tenantId, order.id(), "PENDING");
            long failed = countDetails(tenantId, order.id(), "FAILED");
            String status = pending > 0 ? "PROCESSING" : failed > 0 ? "PARTIAL_FAILED" : "SUCCEEDED";
            jdbc.update("""
                    update payment_profit_sharing_order
                       set provider_order_no=coalesce(?, provider_order_no), status=?, last_error=null,
                           completed_at=case when ? in ('SUCCEEDED','PARTIAL_FAILED') then now() else null end,
                           next_attempt_at=case when ?='PROCESSING' then now()+interval '30 seconds'
                                                else next_attempt_at end,
                           updated_at=now()
                     where tenant_id=? and id=?
                    """, result.providerOrderNo(), status, status, status, tenantId, order.id());
            if (!"PROCESSING".equals(status)) {
                audit.record("PROFIT_SHARING_" + status, "payment_profit_sharing_order", order.id(), null,
                        Map.of("providerOrderNo", result.providerOrderNo() == null ? "" : result.providerOrderNo(),
                                "failedReceivers", failed));
            }
            return null;
        });
    }

    private void postSuccessfulAllocations(UUID tenantId, UUID sharingOrderId) {
        List<Distribution> distributions = jdbc.query("""
                select d.id, d.owner_type, d.organization_id, d.amount_minor, p.channel, p.currency
                  from payment_profit_sharing_detail d
                  join payment_profit_sharing_order s
                    on s.tenant_id=d.tenant_id and s.id=d.sharing_order_id
                  join payment_transaction p
                    on p.tenant_id=s.tenant_id and p.id=s.payment_id
                 where d.tenant_id=? and d.sharing_order_id=? and d.status='SUCCESS'
                """, (result, row) -> new Distribution(
                result.getObject("id", UUID.class), result.getString("owner_type"),
                result.getObject("organization_id", UUID.class), result.getLong("amount_minor"),
                result.getString("channel"), result.getString("currency")), tenantId, sharingOrderId);
        for (Distribution distribution : distributions) postDistribution(tenantId, distribution);
    }

    private void postDistribution(UUID tenantId, Distribution distribution) {
        UUID cash = ensureLedgerAccount(tenantId, "CASH:" + distribution.channel(), "ASSET", distribution.currency());
        String suffix = "PLATFORM".equals(distribution.ownerType()) ? "PLATFORM"
                : "ORG:" + distribution.organizationId();
        UUID expense = ensureLedgerAccount(tenantId, "PROFIT_SHARE:" + suffix, "EXPENSE", distribution.currency());
        UUID transactionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into ledger_transaction
                    (id, tenant_id, reference_type, reference_id, description, occurred_at)
                values (?, ?, 'PROFIT_SHARING', ?, ?, now()) on conflict do nothing
                """, transactionId, tenantId, distribution.id(), "Provider profit sharing " + suffix);
        if (inserted == 0) return;
        jdbc.update("""
                insert into ledger_entry
                    (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency)
                values (?, ?, ?, ?, 'DEBIT', ?, ?), (?, ?, ?, ?, 'CREDIT', ?, ?)
                """, UUID.randomUUID(), tenantId, transactionId, expense,
                distribution.amountMinor(), distribution.currency(), UUID.randomUUID(), tenantId,
                transactionId, cash, distribution.amountMinor(), distribution.currency());
    }

    private UUID ensureLedgerAccount(UUID tenantId, String code, String type, String currency) {
        return jdbc.query("select id from ledger_account where tenant_id=? and account_code=? and currency=?",
                (result, row) -> result.getObject(1, UUID.class), tenantId, code, currency).stream().findFirst()
                .orElseGet(() -> {
                    UUID id = UUID.randomUUID();
                    jdbc.update("""
                            insert into ledger_account (id, tenant_id, account_code, account_type, currency, status)
                            values (?, ?, ?, ?, ?, 'ACTIVE') on conflict do nothing
                            """, id, tenantId, code, type, currency);
                    return jdbc.queryForObject(
                            "select id from ledger_account where tenant_id=? and account_code=? and currency=?",
                            UUID.class, tenantId, code, currency);
                });
    }

    private long countDetails(UUID tenantId, UUID orderId, String status) {
        Long count = jdbc.queryForObject("""
                select count(*) from payment_profit_sharing_detail
                 where tenant_id=? and sharing_order_id=? and status=?
                """, Long.class, tenantId, orderId, status);
        return count == null ? 0 : count;
    }

    private static String detailStatus(PaymentGateway.ProviderState state) {
        return switch (state) {
            case SUCCEEDED -> "SUCCESS";
            case PENDING -> "PENDING";
            case FAILED -> "FAILED";
        };
    }

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 500));
    }

    private record SharingOrder(UUID id, UUID merchantChannelId, String channel, String outOrderNo,
                                String providerOrderNo, String providerTransactionNo) { }
    private record Distribution(UUID id, String ownerType, UUID organizationId,
                                long amountMinor, String channel, String currency) { }
}
