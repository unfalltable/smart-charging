package io.smartcharge.platform.finance;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class ProfitSharingReturnPlanner {
    private final JdbcTemplate jdbc;

    ProfitSharingReturnPlanner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void onRefundSucceeded(UUID tenantId, UUID paymentId, UUID refundId) {
        SharingOrder order = jdbc.query("""
                select s.id, s.provider_order_no, s.status, s.attempts, p.amount_minor payment_amount,
                       (select coalesce(sum(r.amount_minor), 0) from refund_transaction r
                         where r.tenant_id=p.tenant_id and r.payment_id=p.id and r.status='SUCCEEDED') refunded_amount
                  from payment_profit_sharing_order s
                  join payment_transaction p on p.tenant_id=s.tenant_id and p.id=s.payment_id
                 where s.tenant_id=? and s.payment_id=? for update of s
                """, (result, row) -> new SharingOrder(
                result.getObject("id", UUID.class), result.getString("provider_order_no"),
                result.getString("status"), result.getInt("attempts"), result.getLong("payment_amount"),
                result.getLong("refunded_amount")), tenantId, paymentId).stream().findFirst().orElse(null);
        if (order == null || "CANCELLED".equals(order.status())) return;

        if (order.providerOrderNo() == null && order.attempts() == 0 && "PLANNED".equals(order.status())) {
            resizeUnsubmittedPlan(tenantId, order);
            return;
        }
        List<Detail> details = jdbc.query("""
                select id, receiver_account, basis_points, amount_minor from payment_profit_sharing_detail
                 where tenant_id=? and sharing_order_id=? and status<>'CANCELLED'
                 order by receiver_account
                """, (result, row) -> new Detail(result.getObject("id", UUID.class),
                result.getString("receiver_account"), result.getInt("basis_points"),
                result.getLong("amount_minor")), tenantId, order.id());
        for (Detail detail : details) {
            long targetReturned = cumulativeReturn(order.paymentAmount(), order.refundedAmount(),
                    detail.amountMinor(), detail.basisPoints());
            Long alreadyPlanned = jdbc.queryForObject("""
                    select coalesce(sum(amount_minor), 0) from payment_profit_sharing_return
                     where tenant_id=? and sharing_detail_id=? and status<>'CANCELLED'
                    """, Long.class, tenantId, detail.id());
            long amount = targetReturned - (alreadyPlanned == null ? 0 : alreadyPlanned);
            if (amount <= 0) continue;
            UUID returnId = UUID.randomUUID();
            String outReturnNo = "R" + refundId.toString().replace("-", "").substring(0, 20)
                    + detail.id().toString().replace("-", "").substring(0, 20);
            jdbc.update("""
                    insert into payment_profit_sharing_return
                        (id, tenant_id, refund_id, sharing_order_id, sharing_detail_id,
                         out_return_no, receiver_account, amount_minor, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'PLANNED')
                    """, returnId, tenantId, refundId, order.id(), detail.id(), outReturnNo,
                    detail.receiverAccount(), amount);
        }
    }

    private void resizeUnsubmittedPlan(UUID tenantId, SharingOrder order) {
        long remaining = Math.max(0, order.paymentAmount() - order.refundedAmount());
        List<Detail> details = jdbc.query("""
                select id, receiver_account, basis_points, amount_minor from payment_profit_sharing_detail
                 where tenant_id=? and sharing_order_id=? order by receiver_account
                """, (result, row) -> new Detail(result.getObject("id", UUID.class),
                result.getString("receiver_account"), result.getInt("basis_points"),
                result.getLong("amount_minor")), tenantId, order.id());
        long shared = 0;
        for (Detail detail : details) {
            long amount = Math.multiplyExact(remaining, detail.basisPoints()) / 10_000;
            if (amount == 0) {
                jdbc.update("""
                        update payment_profit_sharing_detail
                           set amount_minor=0, status='CANCELLED', updated_at=now()
                         where tenant_id=? and id=?
                        """, tenantId, detail.id());
            } else {
                jdbc.update("""
                        update payment_profit_sharing_detail
                           set amount_minor=?, status='PENDING', fail_reason=null, updated_at=now()
                         where tenant_id=? and id=?
                        """, amount, tenantId, detail.id());
                shared = Math.addExact(shared, amount);
            }
        }
        jdbc.update("""
                update payment_profit_sharing_order
                   set amount_minor=?, status=?, updated_at=now()
                 where tenant_id=? and id=?
                """, shared, shared == 0 ? "CANCELLED" : "PLANNED", tenantId, order.id());
    }

    static long cumulativeReturn(long paymentAmount, long refundedAmount, long sharingAmount, int basisPoints) {
        if (paymentAmount <= 0 || refundedAmount < 0 || refundedAmount > paymentAmount
                || sharingAmount < 0 || basisPoints < 0 || basisPoints > 10_000) {
            throw new IllegalArgumentException("Invalid profit-sharing refund amounts");
        }
        long remainingAllocation = Math.multiplyExact(paymentAmount - refundedAmount, basisPoints) / 10_000;
        return Math.min(sharingAmount, Math.max(0, sharingAmount - remainingAllocation));
    }

    private record SharingOrder(UUID id, String providerOrderNo, String status, int attempts,
                                long paymentAmount, long refundedAmount) { }
    private record Detail(UUID id, String receiverAccount, int basisPoints, long amountMinor) { }
}
