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
final class ProfitSharingReturnJob {
    private static final Logger LOG = LoggerFactory.getLogger(ProfitSharingReturnJob.class);
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PaymentGatewayRegistry gateways;
    private final AuditService audit;

    ProfitSharingReturnJob(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                           PaymentGatewayRegistry gateways, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${payments.profit-sharing-return-delay-ms:30000}")
    void dispatch() {
        List<UUID> tenants = jdbc.query("select id from tenant where status='ACTIVE' order by id",
                (result, row) -> result.getObject(1, UUID.class));
        for (UUID tenantId : tenants) {
            cancelImpossibleReturns(tenantId);
            for (int handled = 0; handled < 50; handled++) {
                ReturnOrder order = claim(tenantId);
                if (order == null) break;
                send(tenantId, order);
            }
        }
    }

    private void cancelImpossibleReturns(UUID tenantId) {
        tenantJdbc.readWriteAs(tenantId, () -> {
            jdbc.update("""
                    update payment_profit_sharing_return r
                       set status='CANCELLED', last_error='Original profit-sharing allocation was not paid',
                           updated_at=now()
                      from payment_profit_sharing_detail d, payment_profit_sharing_order s
                     where r.tenant_id=? and d.tenant_id=r.tenant_id and d.id=r.sharing_detail_id
                       and s.tenant_id=r.tenant_id and s.id=r.sharing_order_id
                       and r.status in ('PLANNED','FAILED') and d.status in ('FAILED','CANCELLED')
                       and s.status in ('PARTIAL_FAILED','CANCELLED')
                    """, tenantId);
            return null;
        });
    }

    private ReturnOrder claim(UUID tenantId) {
        return tenantJdbc.readWriteAs(tenantId, () -> {
            ReturnOrder order = jdbc.query("""
                    select r.id, r.out_return_no, r.receiver_account, r.amount_minor,
                           s.merchant_channel_id, s.channel, s.provider_order_no, s.out_order_no,
                           d.owner_type, d.organization_id, p.currency
                      from payment_profit_sharing_return r
                      join payment_profit_sharing_order s
                        on s.tenant_id=r.tenant_id and s.id=r.sharing_order_id
                      join payment_profit_sharing_detail d
                        on d.tenant_id=r.tenant_id and d.id=r.sharing_detail_id
                      join payment_transaction p
                        on p.tenant_id=s.tenant_id and p.id=s.payment_id
                     where r.tenant_id=? and r.status in ('PLANNED','PROCESSING','FAILED')
                       and r.attempts<20 and r.next_attempt_at<=now()
                       and s.status in ('SUCCEEDED','PARTIAL_FAILED')
                       and s.provider_order_no is not null and d.status='SUCCESS'
                     order by r.next_attempt_at, r.created_at
                     for update of r skip locked limit 1
                    """, (result, row) -> new ReturnOrder(
                    result.getObject("id", UUID.class), result.getString("out_return_no"),
                    result.getString("receiver_account"), result.getLong("amount_minor"),
                    result.getObject("merchant_channel_id", UUID.class), result.getString("channel"),
                    result.getString("provider_order_no"),
                    result.getString("out_order_no"), result.getString("owner_type"),
                    result.getObject("organization_id", UUID.class), result.getString("currency")),
                    tenantId).stream().findFirst().orElse(null);
            if (order == null) return null;
            jdbc.update("""
                    update payment_profit_sharing_return
                       set status='PROCESSING', attempts=attempts+1,
                           next_attempt_at=now()+interval '30 seconds', updated_at=now()
                     where tenant_id=? and id=?
                    """, tenantId, order.id());
            return order;
        });
    }

    private void send(UUID tenantId, ReturnOrder order) {
        try {
            PaymentGateway.GatewayProfitSharingReturn result = gateways.required(order.channel())
                    .returnProfitSharing(new PaymentGateway.GatewayProfitSharingReturnRequest(
                            tenantId, order.merchantChannelId(), order.providerOrderNo(),
                            order.outOrderNo(), order.outReturnNo(),
                            order.receiverAccount(), order.amountMinor(), "用户订单退款分账回退"));
            tenantJdbc.readWriteAs(tenantId, () -> {
                String status = switch (result.state()) {
                    case SUCCEEDED -> "SUCCEEDED";
                    case PENDING -> "PROCESSING";
                    case FAILED -> "FAILED";
                };
                jdbc.update("""
                        update payment_profit_sharing_return
                           set provider_return_no=coalesce(?, provider_return_no), status=?,
                               last_error=?, completed_at=case when ?='SUCCEEDED' then now() else null end,
                               next_attempt_at=case when ? in ('PROCESSING','FAILED')
                                                    then now()+interval '30 seconds' else next_attempt_at end,
                               updated_at=now()
                         where tenant_id=? and id=?
                        """, result.providerReturnNo(), status, result.failReason(), status, status,
                        tenantId, order.id());
                if ("SUCCEEDED".equals(status)) {
                    reverseDistribution(tenantId, order);
                    audit.record("PROFIT_SHARING_RETURN_SUCCEEDED", "payment_profit_sharing_return",
                            order.id(), null, Map.of("amountMinor", order.amountMinor(),
                                    "receiverAccount", order.receiverAccount()));
                }
                return null;
            });
        } catch (RuntimeException failure) {
            tenantJdbc.readWriteAs(tenantId, () -> {
                jdbc.update("""
                        update payment_profit_sharing_return
                           set status='FAILED', last_error=?,
                               next_attempt_at=now()+make_interval(secs => least(1800,
                                   30 * power(2, least(attempts, 6))::integer)), updated_at=now()
                         where tenant_id=? and id=?
                        """, safeMessage(failure), tenantId, order.id());
                return null;
            });
            LOG.warn("Profit-sharing return failed: returnId={}, reason={}",
                    order.id(), failure.getClass().getSimpleName());
        }
    }

    private void reverseDistribution(UUID tenantId, ReturnOrder order) {
        UUID cash = ensureLedgerAccount(tenantId, "CASH:" + order.channel(), "ASSET", order.currency());
        String suffix = "PLATFORM".equals(order.ownerType()) ? "PLATFORM" : "ORG:" + order.organizationId();
        UUID expense = ensureLedgerAccount(tenantId, "PROFIT_SHARE:" + suffix, "EXPENSE", order.currency());
        UUID transactionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into ledger_transaction
                    (id, tenant_id, reference_type, reference_id, description, occurred_at)
                values (?, ?, 'PROFIT_SHARING_RETURN', ?, ?, now()) on conflict do nothing
                """, transactionId, tenantId, order.id(), "Provider profit-sharing return " + suffix);
        if (inserted == 0) return;
        jdbc.update("""
                insert into ledger_entry
                    (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency)
                values (?, ?, ?, ?, 'DEBIT', ?, ?), (?, ?, ?, ?, 'CREDIT', ?, ?)
                """, UUID.randomUUID(), tenantId, transactionId, cash, order.amountMinor(), order.currency(),
                UUID.randomUUID(), tenantId, transactionId, expense, order.amountMinor(), order.currency());
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

    private static String safeMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null ? failure.getClass().getSimpleName()
                : message.substring(0, Math.min(message.length(), 500));
    }

    private record ReturnOrder(UUID id, String outReturnNo, String receiverAccount,
                               long amountMinor, UUID merchantChannelId,
                               String channel, String providerOrderNo,
                               String outOrderNo, String ownerType, UUID organizationId,
                               String currency) { }
}
