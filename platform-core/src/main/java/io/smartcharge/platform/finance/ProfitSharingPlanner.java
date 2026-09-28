package io.smartcharge.platform.finance;

import io.smartcharge.platform.shared.domain.DomainException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class ProfitSharingPlanner {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final int maximumBasisPoints;

    ProfitSharingPlanner(JdbcTemplate jdbc,
                         @Value("${charging.payment.profit-sharing.maximum-basis-points:3000}")
                         int maximumBasisPoints) {
        this.jdbc = jdbc;
        if (maximumBasisPoints < 1 || maximumBasisPoints > 10_000) {
            throw new IllegalArgumentException("profit-sharing maximum basis points must be between 1 and 10000");
        }
        this.maximumBasisPoints = maximumBasisPoints;
    }

    boolean createPlan(UUID tenantId, UUID paymentId, UUID sourceOrganizationId,
                       String channel, long paymentAmountMinor) {
        boolean required = Boolean.TRUE.equals(jdbc.queryForObject("""
                select profit_sharing_required from merchant_channel
                 where tenant_id=? and channel=? and status='ACTIVE'
                """, Boolean.class, tenantId, channel));
        LocalDate businessDate = LocalDate.now(BUSINESS_ZONE);
        List<Policy> policies = jdbc.query("""
                select p.receiver_id, p.basis_points, r.owner_type, r.organization_id,
                       r.receiver_type, r.receiver_account, r.receiver_name
                  from profit_sharing_policy p
                  join profit_sharing_receiver r
                    on r.tenant_id=p.tenant_id and r.id=p.receiver_id and r.status='ACTIVE'
                 where p.tenant_id=? and p.source_organization_id=? and r.channel=?
                   and p.status='ACTIVE' and p.effective_from<=?
                   and (p.effective_until is null or p.effective_until>=?)
                 order by r.owner_type, r.receiver_account
                """, (result, row) -> new Policy(
                result.getObject("receiver_id", UUID.class), result.getInt("basis_points"),
                result.getString("owner_type"), result.getObject("organization_id", UUID.class),
                result.getString("receiver_type"), result.getString("receiver_account"),
                result.getString("receiver_name")),
                tenantId, sourceOrganizationId, channel, businessDate, businessDate);
        if (policies.isEmpty()) {
            if (required) throw new DomainException(
                    "Official provider profit sharing is required, but this station organization has no active policy");
            return false;
        }
        int totalBasisPoints = policies.stream().mapToInt(Policy::basisPoints).sum();
        if (totalBasisPoints > maximumBasisPoints) {
            throw new DomainException("Profit-sharing policy exceeds the approved provider ratio");
        }
        List<Allocation> allocations = policies.stream()
                .map(policy -> new Allocation(policy,
                        Math.multiplyExact(paymentAmountMinor, policy.basisPoints()) / 10_000))
                .filter(allocation -> allocation.amountMinor() > 0)
                .toList();
        if (allocations.isEmpty()) {
            throw new DomainException("Payment amount is too small for the configured profit-sharing policy");
        }
        UUID orderId = UUID.randomUUID();
        String outOrderNo = "S" + paymentId.toString().replace("-", "");
        long sharedAmount = allocations.stream().mapToLong(Allocation::amountMinor).sum();
        jdbc.update("""
                insert into payment_profit_sharing_order
                    (id, tenant_id, payment_id, channel, out_order_no, amount_minor, status)
                values (?, ?, ?, ?, ?, ?, 'PLANNED')
                """, orderId, tenantId, paymentId, channel, outOrderNo, sharedAmount);
        for (Allocation allocation : allocations) {
            Policy policy = allocation.policy();
            jdbc.update("""
                    insert into payment_profit_sharing_detail
                        (id, tenant_id, sharing_order_id, receiver_id, owner_type, organization_id,
                         receiver_type, receiver_account, receiver_name, basis_points, amount_minor, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                    """, UUID.randomUUID(), tenantId, orderId, policy.receiverId(), policy.ownerType(),
                    policy.organizationId(), policy.receiverType(), policy.receiverAccount(),
                    policy.receiverName(), policy.basisPoints(), allocation.amountMinor());
        }
        return true;
    }

    int maximumBasisPoints() {
        return maximumBasisPoints;
    }

    private record Policy(UUID receiverId, int basisPoints, String ownerType, UUID organizationId,
                          String receiverType, String receiverAccount, String receiverName) { }
    private record Allocation(Policy policy, long amountMinor) { }
}
