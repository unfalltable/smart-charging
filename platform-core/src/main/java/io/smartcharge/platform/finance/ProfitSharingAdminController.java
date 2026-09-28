package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.PlatformAuthority;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance/profit-sharing")
final class ProfitSharingAdminController {
    private static final Set<String> RELATIONS = Set.of(
            "SERVICE_PROVIDER", "STORE", "STORE_OWNER", "PARTNER", "HEADQUARTER",
            "BRAND", "DISTRIBUTOR", "SUPPLIER", "CUSTOM");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PaymentGatewayRegistry gateways;
    private final ProfitSharingPlanner planner;
    private final PlatformAuthority platformAuthority;
    private final AuditService audit;

    ProfitSharingAdminController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                                 PaymentGatewayRegistry gateways, ProfitSharingPlanner planner,
                                 PlatformAuthority platformAuthority, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.planner = planner;
        this.platformAuthority = platformAuthority;
        this.audit = audit;
    }

    @GetMapping("/receivers")
    List<ReceiverView> receivers() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select r.id, r.owner_type, r.organization_id, o.name organization_name,
                       r.merchant_channel_id, mc.merchant_id collecting_merchant_id,
                       collector.name collecting_organization_name,
                       r.channel, r.receiver_type, r.receiver_account, r.receiver_name,
                       r.relation_type, r.custom_relation, r.status, r.version, r.created_at
                  from profit_sharing_receiver r
                  left join operator_organization o
                    on o.tenant_id=r.tenant_id and o.id=r.organization_id
                  join merchant_channel mc
                    on mc.tenant_id=r.tenant_id and mc.id=r.merchant_channel_id
                  join operator_organization collector
                    on collector.tenant_id=mc.tenant_id and collector.id=mc.organization_id
                 where r.tenant_id=? order by r.owner_type, o.hierarchy_level, o.name, r.created_at
                """, (result, row) -> new ReceiverView(
                result.getObject("id", UUID.class), result.getString("owner_type"),
                result.getObject("organization_id", UUID.class), result.getString("organization_name"),
                result.getObject("merchant_channel_id", UUID.class),
                result.getString("collecting_merchant_id"),
                result.getString("collecting_organization_name"),
                result.getString("channel"), result.getString("receiver_type"),
                result.getString("receiver_account"), result.getString("receiver_name"),
                result.getString("relation_type"), result.getString("custom_relation"),
                result.getString("status"), result.getLong("version"),
                result.getTimestamp("created_at").toInstant()), tenantId));
    }

    @PostMapping("/receivers")
    @ResponseStatus(HttpStatus.CREATED)
    ReceiverView register(@Valid @RequestBody RegisterReceiverRequest request,
                          Authentication authentication) {
        validateReceiver(request, authentication);
        UUID tenantId = TenantContext.requireTenantId();
        ReceiverView registration = tenantJdbc.readWrite(() -> {
            MerchantRoute collectingRoute = merchantRoute(tenantId, request.merchantChannelId());
            if (!"WECHAT".equals(collectingRoute.channel())) {
                throw new DomainException("Alipay official profit sharing is reserved but not enabled yet");
            }
            if ("ORGANIZATION".equals(request.ownerType())) {
                requireActiveOrganization(tenantId, request.organizationId());
                if (!isAncestorOrSelf(tenantId, collectingRoute.organizationId(), request.organizationId())) {
                    throw new DomainException("A collecting organization may only distribute funds to itself or an upstream organization");
                }
            }
            if (request.receiverAccount().equals(collectingRoute.merchantId())) {
                throw new DomainException("The direct collecting merchant receives the unallocated remainder and must not be added again");
            }
            ReceiverView existing = jdbc.query("""
                    select r.id, r.owner_type, r.organization_id, o.name organization_name,
                           r.merchant_channel_id, mc.merchant_id collecting_merchant_id,
                           collector.name collecting_organization_name,
                           r.channel, r.receiver_type, r.receiver_account, r.receiver_name,
                           r.relation_type, r.custom_relation, r.status, r.version, r.created_at
                      from profit_sharing_receiver r
                      left join operator_organization o
                        on o.tenant_id=r.tenant_id and o.id=r.organization_id
                      join merchant_channel mc
                        on mc.tenant_id=r.tenant_id and mc.id=r.merchant_channel_id
                      join operator_organization collector
                        on collector.tenant_id=mc.tenant_id and collector.id=mc.organization_id
                     where r.tenant_id=? and r.merchant_channel_id=? and r.receiver_account=? for update of r
                    """, (result, row) -> new ReceiverView(
                    result.getObject("id", UUID.class), result.getString("owner_type"),
                    result.getObject("organization_id", UUID.class), result.getString("organization_name"),
                    result.getObject("merchant_channel_id", UUID.class),
                    result.getString("collecting_merchant_id"),
                    result.getString("collecting_organization_name"),
                    result.getString("channel"), result.getString("receiver_type"),
                    result.getString("receiver_account"), result.getString("receiver_name"),
                    result.getString("relation_type"), result.getString("custom_relation"),
                    result.getString("status"), result.getLong("version"),
                    result.getTimestamp("created_at").toInstant()), tenantId, request.merchantChannelId(),
                    request.receiverAccount()).stream().findFirst().orElse(null);
            if (existing != null && "ACTIVE".equals(existing.status())) {
                throw new DomainException("Profit-sharing receiver is already active");
            }
            if (existing != null && "PENDING".equals(existing.status())) {
                throw new DomainException("Profit-sharing receiver registration is already in progress");
            }
            UUID id = existing == null ? UUID.randomUUID() : existing.id();
            if (existing == null) {
                jdbc.update("""
                        insert into profit_sharing_receiver
                            (id, tenant_id, owner_type, organization_id, merchant_channel_id,
                             channel, receiver_type,
                             receiver_account, receiver_name, relation_type, custom_relation, status)
                        values (?, ?, ?, ?, ?, ?, 'MERCHANT_ID', ?, ?, ?, ?, 'PENDING')
                        """, id, tenantId, request.ownerType(), request.organizationId(),
                        request.merchantChannelId(), collectingRoute.channel(),
                        request.receiverAccount(), request.receiverName(), request.relationType(),
                        request.customRelation());
            } else {
                jdbc.update("""
                        update profit_sharing_receiver
                           set owner_type=?, organization_id=?, receiver_name=?, relation_type=?,
                               custom_relation=?, status='PENDING', updated_at=now(), version=version+1
                         where tenant_id=? and id=? and status in ('FAILED','DISABLED')
                        """, request.ownerType(), request.organizationId(), request.receiverName(),
                        request.relationType(), request.customRelation(), tenantId, id);
            }
            return receiver(tenantId, id);
        });
        try {
            gateways.required(registration.channel()).registerProfitSharingReceiver(
                    new PaymentGateway.ProfitSharingReceiver(tenantId, request.merchantChannelId(),
                            request.receiverAccount(),
                            request.receiverName(), request.relationType(), request.customRelation()));
        } catch (RuntimeException failure) {
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update profit_sharing_receiver
                           set status='FAILED', updated_at=now(), version=version+1
                         where tenant_id=? and id=? and status='PENDING'
                        """, tenantId, registration.id());
                return null;
            });
            throw failure;
        }
        return tenantJdbc.readWrite(() -> {
            jdbc.update("""
                    update profit_sharing_receiver
                       set status='ACTIVE', updated_at=now(), version=version+1
                     where tenant_id=? and id=? and status='PENDING'
                    """, tenantId, registration.id());
            ReceiverView view = receiver(tenantId, registration.id());
            audit.record("PROFIT_SHARING_RECEIVER_REGISTERED", "profit_sharing_receiver",
                    registration.id(), registration, view);
            return view;
        });
    }

    @PatchMapping("/receivers/{receiverId}/disable")
    ReceiverView disableReceiver(@PathVariable UUID receiverId, Authentication authentication) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            ReceiverView before = receiver(tenantId, receiverId);
            if ("PLATFORM".equals(before.ownerType())) {
                platformAuthority.requirePlatformAdministrator(authentication);
            }
            boolean inUse = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from profit_sharing_policy
                                   where tenant_id=? and receiver_id=? and status='ACTIVE')
                    """, Boolean.class, tenantId, receiverId));
            if (inUse) throw new DomainException("Disable active profit-sharing policies before disabling the receiver");
            jdbc.update("""
                    update profit_sharing_receiver
                       set status='DISABLED', updated_at=now(), version=version+1
                     where tenant_id=? and id=? and status='ACTIVE'
                    """, tenantId, receiverId);
            ReceiverView after = receiver(tenantId, receiverId);
            audit.record("PROFIT_SHARING_RECEIVER_DISABLED", "profit_sharing_receiver", receiverId, before, after);
            return after;
        });
    }

    @GetMapping("/policies")
    List<PolicyView> policies() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select p.id, p.source_organization_id, source.name source_name, p.receiver_id,
                       r.owner_type, coalesce(target.name, r.receiver_name) receiver_display_name,
                       r.channel, p.basis_points, p.effective_from, p.effective_until,
                       p.status, p.version
                  from profit_sharing_policy p
                  join operator_organization source
                    on source.tenant_id=p.tenant_id and source.id=p.source_organization_id
                  join profit_sharing_receiver r
                    on r.tenant_id=p.tenant_id and r.id=p.receiver_id
                  left join operator_organization target
                    on target.tenant_id=r.tenant_id and target.id=r.organization_id
                 where p.tenant_id=? order by source.hierarchy_level, source.name, r.owner_type
                """, (result, row) -> new PolicyView(
                result.getObject("id", UUID.class), result.getObject("source_organization_id", UUID.class),
                result.getString("source_name"), result.getObject("receiver_id", UUID.class),
                result.getString("owner_type"), result.getString("receiver_display_name"),
                result.getString("channel"), result.getInt("basis_points"),
                result.getObject("effective_from", LocalDate.class),
                result.getObject("effective_until", LocalDate.class), result.getString("status"),
                result.getLong("version")), tenantId));
    }

    @PostMapping("/policies")
    @ResponseStatus(HttpStatus.CREATED)
    PolicyView createPolicy(@Valid @RequestBody CreatePolicyRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            requireActiveOrganization(tenantId, request.sourceOrganizationId());
            // Serialize policy changes for one source organization so two concurrent
            // requests cannot both pass the ratio check and exceed the provider cap.
            jdbc.queryForObject("""
                    select id from operator_organization
                     where tenant_id=? and id=? for update
                    """, UUID.class, tenantId, request.sourceOrganizationId());
            ReceiverIdentity receiver = jdbc.query("""
                    select owner_type, organization_id, merchant_channel_id, channel
                      from profit_sharing_receiver
                     where tenant_id=? and id=? and status='ACTIVE'
                    """, (result, row) -> new ReceiverIdentity(
                    result.getString("owner_type"), result.getObject("organization_id", UUID.class),
                    result.getObject("merchant_channel_id", UUID.class),
                    result.getString("channel")), tenantId, request.receiverId()).stream().findFirst()
                    .orElseThrow(() -> new DomainException("Profit-sharing receiver is not active"));
            MerchantRoute sourceRoute = merchantRouteForOrganization(
                    tenantId, request.sourceOrganizationId(), receiver.channel());
            if (!sourceRoute.id().equals(receiver.merchantChannelId())) {
                throw new DomainException("The receiver was not registered with this organization's collecting merchant");
            }
            if (receiver.organizationId() != null && !isAncestorOrSelf(
                    tenantId, request.sourceOrganizationId(), receiver.organizationId())) {
                throw new DomainException("An organization may only distribute funds to itself or an upstream organization");
            }
            Integer configured = jdbc.queryForObject("""
                    select coalesce(sum(basis_points), 0) from profit_sharing_policy
                     where tenant_id=? and source_organization_id=? and status='ACTIVE'
                       and effective_from<=coalesce(cast(? as date), date '9999-12-31')
                       and (effective_until is null or effective_until>=?)
                    """, Integer.class, tenantId, request.sourceOrganizationId(),
                    request.effectiveUntil(), request.effectiveFrom());
            if ((configured == null ? 0 : configured) + request.basisPoints() > planner.maximumBasisPoints()) {
                throw new DomainException("Total profit-sharing ratio exceeds the provider-approved maximum");
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into profit_sharing_policy
                        (id, tenant_id, source_organization_id, receiver_id, basis_points,
                         effective_from, effective_until, status)
                    values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, tenantId, request.sourceOrganizationId(), request.receiverId(),
                    request.basisPoints(), request.effectiveFrom(), request.effectiveUntil());
            PolicyView view = policy(tenantId, id);
            audit.record("PROFIT_SHARING_POLICY_CREATED", "profit_sharing_policy", id, null, view);
            return view;
        });
    }

    @PatchMapping("/policies/{policyId}/disable")
    PolicyView disablePolicy(@PathVariable UUID policyId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            PolicyView before = policy(tenantId, policyId);
            jdbc.update("""
                    update profit_sharing_policy
                       set status='DISABLED', updated_at=now(), version=version+1
                     where tenant_id=? and id=? and status='ACTIVE'
                    """, tenantId, policyId);
            PolicyView after = policy(tenantId, policyId);
            audit.record("PROFIT_SHARING_POLICY_DISABLED", "profit_sharing_policy", policyId, before, after);
            return after;
        });
    }

    @GetMapping("/orders")
    List<SharingOrderView> orders() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select s.id, s.payment_id, p.merchant_order_no, s.out_order_no, s.provider_order_no,
                       s.channel, s.amount_minor, s.status, s.attempts, s.last_error,
                       s.created_at, s.completed_at
                  from payment_profit_sharing_order s
                  join payment_transaction p on p.tenant_id=s.tenant_id and p.id=s.payment_id
                 where s.tenant_id=? order by s.created_at desc limit 500
                """, (result, row) -> new SharingOrderView(
                result.getObject("id", UUID.class), result.getObject("payment_id", UUID.class),
                result.getString("merchant_order_no"), result.getString("out_order_no"),
                result.getString("provider_order_no"), result.getString("channel"),
                result.getLong("amount_minor"), result.getString("status"), result.getInt("attempts"),
                result.getString("last_error"), result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("completed_at") == null ? null
                        : result.getTimestamp("completed_at").toInstant()), tenantId));
    }

    @GetMapping("/returns")
    List<SharingReturnView> returns() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select r.id, r.refund_id, r.out_return_no, r.provider_return_no,
                       r.receiver_account, r.amount_minor, r.status, r.attempts,
                       r.last_error, r.created_at, r.completed_at
                  from payment_profit_sharing_return r
                 where r.tenant_id=? order by r.created_at desc limit 500
                """, (result, row) -> new SharingReturnView(
                result.getObject("id", UUID.class), result.getObject("refund_id", UUID.class),
                result.getString("out_return_no"), result.getString("provider_return_no"),
                result.getString("receiver_account"), result.getLong("amount_minor"),
                result.getString("status"), result.getInt("attempts"), result.getString("last_error"),
                result.getTimestamp("created_at").toInstant(),
                result.getTimestamp("completed_at") == null ? null
                        : result.getTimestamp("completed_at").toInstant()), tenantId));
    }

    private void validateReceiver(RegisterReceiverRequest request, Authentication authentication) {
        if (!Set.of("PLATFORM", "ORGANIZATION").contains(request.ownerType())) {
            throw new IllegalArgumentException("Invalid receiver owner type");
        }
        if (!RELATIONS.contains(request.relationType())) throw new IllegalArgumentException("Invalid relation type");
        if ("CUSTOM".equals(request.relationType()) != (request.customRelation() != null)) {
            throw new IllegalArgumentException("Custom relation is required only for CUSTOM relation type");
        }
        if ("PLATFORM".equals(request.ownerType())) {
            if (request.organizationId() != null) throw new IllegalArgumentException("Platform receiver cannot have an organization");
            platformAuthority.requirePlatformAdministrator(authentication);
        } else if (request.organizationId() == null) {
            throw new IllegalArgumentException("Organization receiver requires an organization");
        }
    }

    private void requireActiveOrganization(UUID tenantId, UUID organizationId) {
        boolean exists = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from operator_organization
                               where tenant_id=? and id=? and status='ACTIVE')
                """, Boolean.class, tenantId, organizationId));
        if (!exists) throw new DomainException("Organization does not exist or is not active");
    }

    private boolean isAncestorOrSelf(UUID tenantId, UUID sourceId, UUID candidateId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                with recursive lineage as (
                    select id, parent_id from operator_organization where tenant_id=? and id=?
                    union all
                    select parent.id, parent.parent_id from operator_organization parent
                    join lineage child on child.parent_id=parent.id
                    where parent.tenant_id=?
                )
                select exists(select 1 from lineage where id=?)
                """, Boolean.class, tenantId, sourceId, tenantId, candidateId));
    }

    private ReceiverView receiver(UUID tenantId, UUID receiverId) {
        return jdbc.query("""
                select r.id, r.owner_type, r.organization_id, o.name organization_name,
                       r.merchant_channel_id, mc.merchant_id collecting_merchant_id,
                       collector.name collecting_organization_name,
                       r.channel, r.receiver_type, r.receiver_account, r.receiver_name,
                       r.relation_type, r.custom_relation, r.status, r.version, r.created_at
                  from profit_sharing_receiver r
                  left join operator_organization o on o.tenant_id=r.tenant_id and o.id=r.organization_id
                  join merchant_channel mc on mc.tenant_id=r.tenant_id and mc.id=r.merchant_channel_id
                  join operator_organization collector
                    on collector.tenant_id=mc.tenant_id and collector.id=mc.organization_id
                 where r.tenant_id=? and r.id=?
                """, (result, row) -> new ReceiverView(
                result.getObject("id", UUID.class), result.getString("owner_type"),
                result.getObject("organization_id", UUID.class), result.getString("organization_name"),
                result.getObject("merchant_channel_id", UUID.class),
                result.getString("collecting_merchant_id"), result.getString("collecting_organization_name"),
                result.getString("channel"), result.getString("receiver_type"),
                result.getString("receiver_account"), result.getString("receiver_name"),
                result.getString("relation_type"), result.getString("custom_relation"),
                result.getString("status"), result.getLong("version"),
                result.getTimestamp("created_at").toInstant()), tenantId, receiverId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Profit-sharing receiver does not exist"));
    }

    private MerchantRoute merchantRoute(UUID tenantId, UUID merchantChannelId) {
        return jdbc.query("""
                select id, organization_id, channel, merchant_id from merchant_channel
                 where tenant_id=? and id=? and status='ACTIVE'
                """, (result, row) -> new MerchantRoute(
                result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                result.getString("channel"), result.getString("merchant_id")),
                tenantId, merchantChannelId).stream().findFirst()
                .orElseThrow(() -> new DomainException("Collecting merchant channel is not active"));
    }

    private MerchantRoute merchantRouteForOrganization(UUID tenantId, UUID organizationId, String channel) {
        return jdbc.query("""
                select id, organization_id, channel, merchant_id from merchant_channel
                 where tenant_id=? and organization_id=? and channel=? and status='ACTIVE'
                """, (result, row) -> new MerchantRoute(
                result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                result.getString("channel"), result.getString("merchant_id")),
                tenantId, organizationId, channel).stream().findFirst()
                .orElseThrow(() -> new DomainException(
                        "The source organization has no active direct collecting merchant channel"));
    }

    private PolicyView policy(UUID tenantId, UUID policyId) {
        return jdbc.query("""
                select p.id, p.source_organization_id, source.name source_name, p.receiver_id,
                       r.owner_type, coalesce(target.name, r.receiver_name) receiver_display_name,
                       r.channel, p.basis_points, p.effective_from, p.effective_until,
                       p.status, p.version
                  from profit_sharing_policy p
                  join operator_organization source
                    on source.tenant_id=p.tenant_id and source.id=p.source_organization_id
                  join profit_sharing_receiver r on r.tenant_id=p.tenant_id and r.id=p.receiver_id
                  left join operator_organization target
                    on target.tenant_id=r.tenant_id and target.id=r.organization_id
                 where p.tenant_id=? and p.id=?
                """, (result, row) -> new PolicyView(
                result.getObject("id", UUID.class), result.getObject("source_organization_id", UUID.class),
                result.getString("source_name"), result.getObject("receiver_id", UUID.class),
                result.getString("owner_type"), result.getString("receiver_display_name"),
                result.getString("channel"), result.getInt("basis_points"),
                result.getObject("effective_from", LocalDate.class),
                result.getObject("effective_until", LocalDate.class), result.getString("status"),
                result.getLong("version")), tenantId, policyId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Profit-sharing policy does not exist"));
    }

    record RegisterReceiverRequest(
            @NotBlank String ownerType, UUID organizationId, @NotNull UUID merchantChannelId,
            @NotBlank @Pattern(regexp = "[0-9]{6,32}") String receiverAccount,
            @NotBlank @Size(max = 160) String receiverName,
            @NotBlank String relationType,
            @Size(max = 10) String customRelation) { }
    record CreatePolicyRequest(
            @NotNull UUID sourceOrganizationId, @NotNull UUID receiverId,
            @Min(1) @Max(10_000) int basisPoints,
            @NotNull LocalDate effectiveFrom, LocalDate effectiveUntil) {
        CreatePolicyRequest {
            if (effectiveUntil != null && effectiveFrom != null && effectiveUntil.isBefore(effectiveFrom)) {
                throw new IllegalArgumentException("Policy end date cannot precede its start date");
            }
        }
    }
    record ReceiverView(UUID id, String ownerType, UUID organizationId, String organizationName,
                        UUID merchantChannelId, String collectingMerchantId,
                        String collectingOrganizationName,
                        String channel, String receiverType, String receiverAccount, String receiverName,
                        String relationType, String customRelation, String status, long version,
                        Instant createdAt) { }
    record PolicyView(UUID id, UUID sourceOrganizationId, String sourceOrganizationName,
                      UUID receiverId, String receiverOwnerType, String receiverDisplayName,
                      String channel, int basisPoints, LocalDate effectiveFrom,
                      LocalDate effectiveUntil, String status, long version) { }
    record SharingOrderView(UUID id, UUID paymentId, String merchantOrderNo, String outOrderNo,
                            String providerOrderNo, String channel, long amountMinor, String status,
                            int attempts, String lastError, Instant createdAt, Instant completedAt) { }
    record SharingReturnView(UUID id, UUID refundId, String outReturnNo, String providerReturnNo,
                             String receiverAccount, long amountMinor, String status, int attempts,
                             String lastError, Instant createdAt, Instant completedAt) { }
    private record ReceiverIdentity(String ownerType, UUID organizationId,
                                    UUID merchantChannelId, String channel) { }
    private record MerchantRoute(UUID id, UUID organizationId, String channel, String merchantId) { }
}
