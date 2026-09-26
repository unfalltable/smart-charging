package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/finance")
final class FinanceAdminController {
    private static final Set<String> CHANNELS = Set.of("WECHAT", "ALIPAY", "BALANCE");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final PaymentGatewayRegistry gateways;
    private final AuditService audit;
    private final RefundCallbackService refunds;

    FinanceAdminController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                           PaymentGatewayRegistry gateways, AuditService audit,
                           RefundCallbackService refunds) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.gateways = gateways;
        this.audit = audit;
        this.refunds = refunds;
    }

    @GetMapping("/merchant-channels")
    List<MerchantChannelView> merchantChannels() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, channel, merchant_id, application_id, secret_reference, notify_url,
                       refund_notify_url, status, version
                  from merchant_channel where tenant_id=? order by channel, merchant_id
                """, (result, row) -> new MerchantChannelView(
                    result.getObject("id", UUID.class), result.getString("channel"),
                    result.getString("merchant_id"), result.getString("application_id"),
                    result.getString("secret_reference"), result.getString("notify_url"),
                    result.getString("refund_notify_url"), result.getString("status"),
                    result.getLong("version")), tenantId));
    }

    @PostMapping("/merchant-channels")
    @ResponseStatus(HttpStatus.CREATED)
    MerchantChannelView createMerchantChannel(@Valid @RequestBody MerchantChannelRequest request) {
        if (!Set.of("WECHAT", "ALIPAY").contains(request.channel())) {
            throw new IllegalArgumentException("Unsupported merchant channel");
        }
        if (!Set.of("DISABLED", "TESTING", "ACTIVE").contains(request.status())) {
            throw new IllegalArgumentException("Invalid merchant channel status");
        }
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into merchant_channel
                        (id, tenant_id, channel, merchant_id, application_id, secret_reference,
                         notify_url, refund_notify_url, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, request.channel(), request.merchantId(), request.applicationId(),
                    request.secretReference(), request.notifyUrl(), request.refundNotifyUrl(), request.status());
            MerchantChannelView view = new MerchantChannelView(id, request.channel(), request.merchantId(),
                    request.applicationId(), request.secretReference(), request.notifyUrl(),
                    request.refundNotifyUrl(), request.status(), 0);
            audit.record("MERCHANT_CHANNEL_CREATED", "merchant_channel", id, null, view);
            return view;
        });
    }

    @PatchMapping("/merchant-channels/{channelId}")
    MerchantChannelView updateMerchantChannel(@PathVariable UUID channelId,
                                              @Valid @RequestBody UpdateMerchantChannelRequest request) {
        if (!Set.of("DISABLED", "TESTING", "ACTIVE").contains(request.status())) {
            throw new IllegalArgumentException("Invalid merchant channel status");
        }
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            MerchantChannelView before = merchantChannel(tenantId, channelId);
            int changed = jdbc.update("""
                    update merchant_channel
                       set secret_reference=?, notify_url=?, refund_notify_url=?, status=?,
                           updated_at=now(), version=version+1
                     where tenant_id=? and id=? and version=?
                    """, request.secretReference(), request.notifyUrl(), request.refundNotifyUrl(), request.status(),
                    tenantId, channelId, request.version());
            if (changed != 1) throw new DomainException("Merchant channel was modified by another operator");
            MerchantChannelView after = merchantChannel(tenantId, channelId);
            audit.record("MERCHANT_CHANNEL_UPDATED", "merchant_channel", channelId, before, after);
            return after;
        });
    }

    @PostMapping("/wallet-adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> adjustWallet(@Valid @RequestBody WalletAdjustmentRequest request) {
        if (!Set.of("CREDIT", "DEBIT").contains(request.direction())) {
            throw new IllegalArgumentException("Invalid wallet adjustment direction");
        }
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            jdbc.update("""
                    insert into wallet_account (id, tenant_id, customer_id, currency, status)
                    values (?, ?, ?, 'CNY', 'ACTIVE') on conflict (tenant_id, customer_id, currency) do nothing
                    """, UUID.randomUUID(), tenantId, request.customerId());
            WalletBalance wallet = jdbc.query("""
                    select id, balance_minor from wallet_account
                     where tenant_id=? and customer_id=? and currency='CNY' and status='ACTIVE' for update
                    """, (result, row) -> new WalletBalance(
                    result.getObject("id", UUID.class), result.getLong("balance_minor")), tenantId, request.customerId())
                    .stream().findFirst().orElseThrow(() -> new DomainException("Customer wallet is unavailable"));
            long next = "CREDIT".equals(request.direction())
                    ? Math.addExact(wallet.balanceMinor(), request.amountMinor())
                    : wallet.balanceMinor() - request.amountMinor();
            if (next < 0) throw new DomainException("Wallet balance is insufficient");
            jdbc.update("""
                    update wallet_account set balance_minor=?, updated_at=now(), version=version+1
                     where tenant_id=? and id=?
                    """, next, tenantId, wallet.id());
            UUID referenceId = UUID.randomUUID();
            jdbc.update("""
                    insert into wallet_entry
                        (id, tenant_id, wallet_id, reference_type, reference_id, direction, amount_minor, balance_after_minor)
                    values (?, ?, ?, 'MANUAL_ADJUSTMENT', ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenantId, wallet.id(), referenceId, request.direction(),
                    request.amountMinor(), next);
            audit.record("WALLET_ADJUSTED", "wallet_account", wallet.id(),
                    Map.of("balanceMinor", wallet.balanceMinor()),
                    Map.of("balanceMinor", next, "reason", request.reason(), "referenceId", referenceId));
            return Map.of("walletId", wallet.id(), "balanceMinor", next, "referenceId", referenceId);
        });
    }

    @GetMapping("/payments")
    List<PaymentView> payments(@RequestParam(required = false) String status,
                               @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select p.id, p.order_id, o.order_no, p.channel, p.transaction_type, p.merchant_order_no,
                       p.provider_transaction_no, p.amount_minor, p.currency, p.status,
                       p.created_at, p.completed_at
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and (cast(? as varchar) is null or p.status=?)
                 order by p.created_at desc limit ?
                """, (result, row) -> new PaymentView(
                    result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                    result.getString("order_no"), result.getString("channel"),
                    result.getString("transaction_type"), result.getString("merchant_order_no"),
                    result.getString("provider_transaction_no"), result.getLong("amount_minor"),
                    result.getString("currency"), result.getString("status"),
                    result.getTimestamp("created_at").toInstant(), timestamp(result.getTimestamp("completed_at"))),
                tenantId, blankToNull(status), blankToNull(status), limit));
    }

    @GetMapping("/refunds")
    List<RefundView> refunds(@RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select r.id, r.payment_id, r.merchant_refund_no, r.provider_refund_no,
                       r.amount_minor, r.status, r.reason, r.created_at, r.completed_at
                  from refund_transaction r where r.tenant_id=? order by r.created_at desc limit ?
                """, (result, row) -> new RefundView(
                    result.getObject("id", UUID.class), result.getObject("payment_id", UUID.class),
                    result.getString("merchant_refund_no"), result.getString("provider_refund_no"),
                    result.getLong("amount_minor"), result.getString("status"), result.getString("reason"),
                    result.getTimestamp("created_at").toInstant(), timestamp(result.getTimestamp("completed_at"))),
                tenantId, limit));
    }

    @PostMapping("/refunds")
    @ResponseStatus(HttpStatus.ACCEPTED)
    RefundView createRefund(@Valid @RequestBody CreateRefundRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        RefundSeed seed = tenantJdbc.readWrite(() -> prepareRefund(tenantId, request));
        try {
            PaymentGateway.GatewayRefund gatewayRefund = gateways.required(seed.channel()).createRefund(
                    new PaymentGateway.GatewayRefundRequest(tenantId, seed.id(), seed.merchantRefundNo(),
                            seed.providerTransactionNo(), seed.amountMinor(), seed.originalPaymentAmountMinor(),
                            seed.currency(), seed.reason()));
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update refund_transaction set provider_refund_no=?, status='PROCESSING', updated_at=now()
                         where tenant_id=? and id=? and status='CREATED'
                        """, gatewayRefund.providerRefundNo(), tenantId, seed.id());
                return null;
            });
            if (gatewayRefund.completed()) {
                refunds.applyProviderStatus(tenantId, seed.id(), new PaymentGateway.GatewayRefundStatus(
                        gatewayRefund.providerRefundNo(), seed.amountMinor(), PaymentGateway.ProviderState.SUCCEEDED));
            }
            return tenantJdbc.readWrite(() -> refundView(tenantId, seed.id()));
        } catch (RuntimeException failure) {
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update refund_transaction set status='PROCESSING', updated_at=now()
                         where tenant_id=? and id=? and status='CREATED'
                        """,
                        tenantId, seed.id());
                return null;
            });
            throw failure;
        }
    }

    @PostMapping("/reconciliation")
    @ResponseStatus(HttpStatus.CREATED)
    ReconciliationResult reconcile(@Valid @RequestBody ReconciliationRequest request) {
        if (!CHANNELS.contains(request.channel())) throw new IllegalArgumentException("Unsupported channel");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID batchId = UUID.randomUUID();
            jdbc.update("""
                    insert into reconciliation_batch
                        (id, tenant_id, channel, statement_date, status, source_file_hash, total_count)
                    values (?, ?, ?, ?, 'PROCESSING', ?, ?)
                    """, batchId, tenantId, request.channel(), request.statementDate(),
                    request.sourceFileHash(), request.rows().size());
            int matched = 0;
            int exceptions = 0;
            for (ReconciliationRow row : request.rows()) {
                PaymentMatch payment = jdbc.query("""
                        select id, amount_minor, provider_transaction_no from payment_transaction
                         where tenant_id=? and channel=? and merchant_order_no=? and status='SUCCEEDED'
                        """, (result, index) -> new PaymentMatch(
                        result.getObject("id", UUID.class), result.getLong("amount_minor"),
                        result.getString("provider_transaction_no")), tenantId, request.channel(), row.merchantOrderNo())
                        .stream().findFirst().orElse(null);
                String result;
                String detail = null;
                if (payment == null) {
                    result = "MISSING_PLATFORM";
                    detail = "Provider transaction has no successful platform payment";
                    exceptions++;
                } else if (payment.amountMinor() != row.amountMinor()) {
                    result = "AMOUNT_MISMATCH";
                    detail = "Provider and platform amounts differ";
                    exceptions++;
                } else {
                    result = "MATCHED";
                    matched++;
                }
                jdbc.update("""
                        insert into reconciliation_item
                            (id, tenant_id, batch_id, payment_id, merchant_order_no, provider_transaction_no,
                             provider_amount_minor, platform_amount_minor, result, detail)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, UUID.randomUUID(), tenantId, batchId, payment == null ? null : payment.id(),
                        row.merchantOrderNo(), row.providerTransactionNo(), row.amountMinor(),
                        payment == null ? null : payment.amountMinor(), result, detail);
            }
            String status = exceptions == 0 ? "MATCHED" : "EXCEPTION";
            jdbc.update("""
                    update reconciliation_batch set status=?, matched_count=?, exception_count=?, completed_at=now()
                     where tenant_id=? and id=?
                    """, status, matched, exceptions, tenantId, batchId);
            audit.record("RECONCILIATION_COMPLETED", "reconciliation_batch", batchId, null,
                    Map.of("status", status, "matched", matched, "exceptions", exceptions));
            return new ReconciliationResult(batchId, status, request.rows().size(), matched, exceptions);
        });
    }

    @GetMapping("/reconciliation")
    List<ReconciliationResult> reconciliationBatches() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, status, total_count, matched_count, exception_count
                  from reconciliation_batch where tenant_id=? order by created_at desc limit 200
                """, (result, row) -> new ReconciliationResult(
                    result.getObject("id", UUID.class), result.getString("status"),
                    result.getInt("total_count"), result.getInt("matched_count"),
                    result.getInt("exception_count")), tenantId));
    }

    @PostMapping("/settlement-rules")
    @ResponseStatus(HttpStatus.CREATED)
    SettlementRuleView createSettlementRule(@Valid @RequestBody CreateSettlementRuleRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            boolean activeOrganization = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from operator_organization
                                   where tenant_id=? and id=? and status='ACTIVE')
                    """, Boolean.class, tenantId, request.organizationId()));
            if (!activeOrganization) throw new DomainException("Settlement organization does not exist or is not active");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into settlement_rule
                        (id, tenant_id, organization_id, name, beneficiary_code, share_basis_points,
                         platform_service_fee_basis_points, fixed_service_fee_minor,
                         effective_from, effective_until, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                    """, id, tenantId, request.organizationId(), request.name(), request.beneficiaryCode(),
                    request.shareBasisPoints(), request.platformServiceFeeBasisPoints(),
                    request.fixedServiceFeeMinor(), request.effectiveFrom(), request.effectiveUntil());
            SettlementRuleView view = settlementRule(tenantId, id);
            audit.record("SETTLEMENT_RULE_CREATED", "settlement_rule", id, null, view);
            return view;
        });
    }

    @GetMapping("/settlement-rules")
    List<SettlementRuleView> settlementRules() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select r.id, r.organization_id, o.name organization_name, r.name, r.beneficiary_code,
                       r.share_basis_points, r.platform_service_fee_basis_points, r.fixed_service_fee_minor,
                       r.effective_from, r.effective_until, r.status
                  from settlement_rule r
                  join operator_organization o on o.tenant_id=r.tenant_id and o.id=r.organization_id
                 where r.tenant_id=? order by r.created_at desc
                """, (result, row) -> new SettlementRuleView(
                    result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                    result.getString("organization_name"), result.getString("name"),
                    result.getString("beneficiary_code"), result.getInt("share_basis_points"),
                    result.getInt("platform_service_fee_basis_points"),
                    result.getLong("fixed_service_fee_minor"),
                    result.getObject("effective_from", LocalDate.class),
                    result.getObject("effective_until", LocalDate.class), result.getString("status")), tenantId));
    }

    @PostMapping("/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    SettlementView generateSettlement(@Valid @RequestBody GenerateSettlementRequest request) {
        if (request.periodEnd().isBefore(request.periodStart())) throw new IllegalArgumentException("Invalid period");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            Rule rule = jdbc.query("""
                    select r.organization_id, r.share_basis_points, r.platform_service_fee_basis_points,
                           r.fixed_service_fee_minor, o.hierarchy_level
                      from settlement_rule r
                      join operator_organization o on o.tenant_id=r.tenant_id and o.id=r.organization_id
                     where r.tenant_id=? and r.id=? and r.status='ACTIVE' and o.status='ACTIVE'
                       and effective_from<=? and (effective_until is null or effective_until>=?)
                    """, (result, row) -> new Rule(
                    result.getObject("organization_id", UUID.class), result.getInt("share_basis_points"),
                    result.getInt("platform_service_fee_basis_points"),
                    result.getLong("fixed_service_fee_minor"), result.getInt("hierarchy_level")),
                    tenantId, request.ruleId(),
                    request.periodEnd(), request.periodStart()).stream().findFirst()
                    .orElseThrow(() -> new DomainException("Settlement rule is not active for the period"));
            Instant start = request.periodStart().atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant endExclusive = request.periodEnd().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Long payments = jdbc.queryForObject("""
                    select coalesce(sum(p.amount_minor),0)
                      from payment_transaction p
                      join charging_order co on co.tenant_id=p.tenant_id and co.id=p.order_id
                      join connector c on c.tenant_id=co.tenant_id and c.id=co.connector_id
                      join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                      join station s on s.tenant_id=d.tenant_id and s.id=d.station_id
                     where p.tenant_id=? and p.status='SUCCEEDED' and p.completed_at>=? and p.completed_at<?
                       and (?=1 or s.organization_id=?)
                    """, Long.class, tenantId, JdbcTimes.timestamp(start), JdbcTimes.timestamp(endExclusive),
                    rule.hierarchyLevel(), rule.organizationId());
            Long refunds = jdbc.queryForObject("""
                    select coalesce(sum(r.amount_minor),0) from refund_transaction r
                    join payment_transaction p on p.tenant_id=r.tenant_id and p.id=r.payment_id
                    join charging_order co on co.tenant_id=p.tenant_id and co.id=p.order_id
                    join connector c on c.tenant_id=co.tenant_id and c.id=co.connector_id
                    join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                    join station s on s.tenant_id=d.tenant_id and s.id=d.station_id
                     where r.tenant_id=? and r.status='SUCCEEDED' and r.completed_at>=? and r.completed_at<?
                       and (?=1 or s.organization_id=?)
                    """, Long.class, tenantId, JdbcTimes.timestamp(start), JdbcTimes.timestamp(endExclusive),
                    rule.hierarchyLevel(), rule.organizationId());
            long gross = Math.max(0, value(payments) - value(refunds));
            SettlementCalculator.Amounts amounts = SettlementCalculator.calculate(gross,
                    rule.platformServiceFeeBasisPoints(), rule.fixedServiceFeeMinor(), rule.shareBasisPoints());
            long platformFee = amounts.platformServiceFeeMinor();
            long settlement = amounts.beneficiarySettlementMinor();
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into settlement_statement
                        (id, tenant_id, rule_id, period_start, period_end,
                         gross_amount_minor, platform_service_fee_minor, settlement_amount_minor, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                    """, id, tenantId, request.ruleId(), request.periodStart(), request.periodEnd(), gross,
                    platformFee, settlement);
            SettlementView view = new SettlementView(id, request.ruleId(), request.periodStart(), request.periodEnd(),
                    gross, platformFee, settlement, "DRAFT");
            audit.record("SETTLEMENT_GENERATED", "settlement_statement", id, null, view);
            return view;
        });
    }

    @PostMapping("/settlements/{statementId}/transition")
    Map<String, Object> transitionSettlement(@PathVariable UUID statementId,
                                              @Valid @RequestBody SettlementTransitionRequest request) {
        Map<String, String> next = Map.of("DRAFT", "CONFIRMED", "CONFIRMED", "PAYING", "PAYING", "PAID");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            String current = jdbc.query("""
                    select status from settlement_statement where tenant_id=? and id=? for update
                    """, (result, row) -> result.getString(1), tenantId, statementId).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Settlement statement does not exist"));
            if (!request.status().equals(next.get(current))) throw new DomainException("Invalid settlement transition");
            jdbc.update("""
                    update settlement_statement set status=?,
                        confirmed_at=case when ?='CONFIRMED' then now() else confirmed_at end,
                        paid_at=case when ?='PAID' then now() else paid_at end, updated_at=now()
                     where tenant_id=? and id=?
                    """, request.status(), request.status(), request.status(), tenantId, statementId);
            audit.record("SETTLEMENT_TRANSITIONED", "settlement_statement", statementId,
                    Map.of("status", current), Map.of("status", request.status()));
            return Map.of("id", statementId, "status", request.status());
        });
    }

    @GetMapping("/settlements")
    List<SettlementView> settlements() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, rule_id, period_start, period_end, gross_amount_minor,
                       platform_service_fee_minor, settlement_amount_minor, status
                  from settlement_statement where tenant_id=? order by created_at desc limit 200
                """, (result, row) -> new SettlementView(
                    result.getObject("id", UUID.class), result.getObject("rule_id", UUID.class),
                    result.getObject("period_start", LocalDate.class), result.getObject("period_end", LocalDate.class),
                    result.getLong("gross_amount_minor"), result.getLong("platform_service_fee_minor"),
                    result.getLong("settlement_amount_minor"),
                    result.getString("status")), tenantId));
    }

    @GetMapping("/invoices")
    List<InvoiceView> invoices() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, order_id, title, tax_number, email, amount_minor, status, invoice_url, created_at
                  from invoice_request where tenant_id=? order by created_at desc limit 200
                """, (result, row) -> new InvoiceView(
                    result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                    result.getString("title"), result.getString("tax_number"), result.getString("email"),
                    result.getLong("amount_minor"), result.getString("status"), result.getString("invoice_url"),
                    result.getTimestamp("created_at").toInstant()), tenantId));
    }

    @PostMapping("/invoices/{invoiceId}/issue")
    InvoiceView issueInvoice(@PathVariable UUID invoiceId, @Valid @RequestBody IssueInvoiceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update invoice_request set status='ISSUED', invoice_url=?, issued_at=now(), updated_at=now()
                     where tenant_id=? and id=? and status in ('SUBMITTED','PROCESSING')
                    """, request.invoiceUrl(), tenantId, invoiceId);
            if (changed != 1) throw new DomainException("Invoice request cannot be issued");
            audit.record("INVOICE_ISSUED", "invoice_request", invoiceId, null,
                    Map.of("invoiceUrl", request.invoiceUrl()));
            return jdbc.query("""
                    select id, order_id, title, tax_number, email, amount_minor, status, invoice_url, created_at
                      from invoice_request where tenant_id=? and id=?
                    """, (result, row) -> new InvoiceView(
                    result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                    result.getString("title"), result.getString("tax_number"), result.getString("email"),
                    result.getLong("amount_minor"), result.getString("status"), result.getString("invoice_url"),
                    result.getTimestamp("created_at").toInstant()), tenantId, invoiceId).getFirst();
        });
    }

    @PostMapping("/invoices/{invoiceId}/red-issue")
    InvoiceView redIssueInvoice(@PathVariable UUID invoiceId, @Valid @RequestBody CreditInvoiceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update invoice_request set status='RED_ISSUED', credit_note_url=?, red_issued_at=now(), updated_at=now()
                     where tenant_id=? and id=? and status='ISSUED'
                    """, request.creditNoteUrl(), tenantId, invoiceId);
            if (changed != 1) throw new DomainException("Only an issued invoice can be red-invoiced");
            audit.record("INVOICE_RED_ISSUED", "invoice_request", invoiceId, null,
                    Map.of("creditNoteUrl", request.creditNoteUrl(), "reason", request.reason()));
            return invoiceView(tenantId, invoiceId);
        });
    }

    @PostMapping("/invoices/{invoiceId}/reject")
    InvoiceView rejectInvoice(@PathVariable UUID invoiceId, @Valid @RequestBody RejectInvoiceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update invoice_request set status='REJECTED', updated_at=now()
                     where tenant_id=? and id=? and status in ('SUBMITTED','PROCESSING')
                    """, tenantId, invoiceId);
            if (changed != 1) throw new DomainException("Invoice request cannot be rejected");
            audit.record("INVOICE_REJECTED", "invoice_request", invoiceId, null,
                    Map.of("reason", request.reason()));
            return invoiceView(tenantId, invoiceId);
        });
    }

    private RefundSeed prepareRefund(UUID tenantId, CreateRefundRequest request) {
        PaymentForRefund payment = jdbc.query("""
                select id, order_id, channel, provider_transaction_no, amount_minor, currency
                  from payment_transaction where tenant_id=? and id=? and status='SUCCEEDED' for update
                """, (result, row) -> new PaymentForRefund(
                result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                result.getString("channel"), result.getString("provider_transaction_no"),
                result.getLong("amount_minor"), result.getString("currency")), tenantId, request.paymentId())
                .stream().findFirst().orElseThrow(() -> new DomainException("Only a successful payment can be refunded"));
        boolean invoiceBlocksRefund = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from invoice_request
                               where tenant_id=? and order_id=? and status in ('SUBMITTED','PROCESSING','ISSUED'))
                """, Boolean.class, tenantId, payment.orderId()));
        if (invoiceBlocksRefund) {
            throw new DomainException("The invoice must be rejected or red-invoiced before refunding this order");
        }
        Long refunded = jdbc.queryForObject("""
                select coalesce(sum(amount_minor),0) from refund_transaction
                 where tenant_id=? and payment_id=? and status in ('CREATED','PROCESSING','SUCCEEDED')
                """, Long.class, tenantId, payment.id());
        if (request.amountMinor() > payment.amountMinor() - value(refunded)) {
            throw new DomainException("Refund exceeds the remaining refundable amount");
        }
        UUID id = UUID.randomUUID();
        String number = "R" + Instant.now().toEpochMilli() + id.toString().replace("-", "").substring(0, 8);
        jdbc.update("""
                insert into refund_transaction
                    (id, tenant_id, payment_id, merchant_refund_no, amount_minor, status, reason)
                values (?, ?, ?, ?, ?, 'CREATED', ?)
                """, id, tenantId, payment.id(), number, request.amountMinor(), request.reason());
        audit.record("REFUND_CREATED", "refund_transaction", id, null, request);
        return new RefundSeed(id, payment.id(), payment.orderId(), number, payment.channel(),
                payment.providerTransactionNo(), request.amountMinor(), payment.amountMinor(),
                payment.currency(), request.reason());
    }

    private RefundView refundView(UUID tenantId, UUID refundId) {
        return jdbc.query("""
                select id, payment_id, merchant_refund_no, provider_refund_no, amount_minor,
                       status, reason, created_at, completed_at
                  from refund_transaction where tenant_id=? and id=?
                """, (result, row) -> new RefundView(
                result.getObject("id", UUID.class), result.getObject("payment_id", UUID.class),
                result.getString("merchant_refund_no"), result.getString("provider_refund_no"),
                result.getLong("amount_minor"), result.getString("status"), result.getString("reason"),
                result.getTimestamp("created_at").toInstant(), timestamp(result.getTimestamp("completed_at"))),
                tenantId, refundId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Refund does not exist"));
    }

    private InvoiceView invoiceView(UUID tenantId, UUID invoiceId) {
        return jdbc.query("""
                select id, order_id, title, tax_number, email, amount_minor, status, invoice_url, created_at
                  from invoice_request where tenant_id=? and id=?
                """, (result, row) -> new InvoiceView(
                result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                result.getString("title"), result.getString("tax_number"), result.getString("email"),
                result.getLong("amount_minor"), result.getString("status"), result.getString("invoice_url"),
                result.getTimestamp("created_at").toInstant()), tenantId, invoiceId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Invoice request does not exist"));
    }

    private static long value(Long value) { return value == null ? 0 : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static Instant timestamp(java.sql.Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    private MerchantChannelView merchantChannel(UUID tenantId, UUID channelId) {
        return jdbc.query("""
                select id, channel, merchant_id, application_id, secret_reference, notify_url,
                       refund_notify_url, status, version
                  from merchant_channel where tenant_id=? and id=?
                """, (result, row) -> new MerchantChannelView(
                result.getObject("id", UUID.class), result.getString("channel"), result.getString("merchant_id"),
                result.getString("application_id"), result.getString("secret_reference"),
                result.getString("notify_url"), result.getString("refund_notify_url"),
                result.getString("status"), result.getLong("version")), tenantId, channelId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Merchant channel does not exist"));
    }

    private SettlementRuleView settlementRule(UUID tenantId, UUID ruleId) {
        return jdbc.query("""
                select r.id, r.organization_id, o.name organization_name, r.name, r.beneficiary_code,
                       r.share_basis_points, r.platform_service_fee_basis_points, r.fixed_service_fee_minor,
                       r.effective_from, r.effective_until, r.status
                  from settlement_rule r
                  join operator_organization o on o.tenant_id=r.tenant_id and o.id=r.organization_id
                 where r.tenant_id=? and r.id=?
                """, (result, row) -> new SettlementRuleView(
                result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                result.getString("organization_name"), result.getString("name"),
                result.getString("beneficiary_code"), result.getInt("share_basis_points"),
                result.getInt("platform_service_fee_basis_points"), result.getLong("fixed_service_fee_minor"),
                result.getObject("effective_from", LocalDate.class),
                result.getObject("effective_until", LocalDate.class), result.getString("status")),
                tenantId, ruleId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Settlement rule does not exist"));
    }

    record CreateRefundRequest(@NotNull UUID paymentId, @Min(1) long amountMinor,
                               @NotBlank @Size(max = 500) String reason) { }
    record ReconciliationRequest(@NotBlank String channel, @NotNull LocalDate statementDate,
                                 @NotBlank @Size(max = 128) String sourceFileHash,
                                 @NotEmpty @Size(max = 10_000) List<@Valid ReconciliationRow> rows) { }
    record ReconciliationRow(@NotBlank @Size(max = 64) String merchantOrderNo,
                             @Size(max = 128) String providerTransactionNo, @Min(0) long amountMinor) { }
    record CreateSettlementRuleRequest(@NotBlank @Size(max = 160) String name,
                                       @NotNull UUID organizationId,
                                       @NotBlank @Size(max = 96) String beneficiaryCode,
                                       @Min(0) @Max(10_000) int shareBasisPoints,
                                       @Min(0) @Max(10_000) int platformServiceFeeBasisPoints,
                                       @Min(0) long fixedServiceFeeMinor,
                                       @NotNull LocalDate effectiveFrom, LocalDate effectiveUntil) { }
    record GenerateSettlementRequest(@NotNull UUID ruleId, @NotNull LocalDate periodStart,
                                     @NotNull LocalDate periodEnd) { }
    record SettlementTransitionRequest(@NotBlank String status) { }
    record IssueInvoiceRequest(@NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String invoiceUrl) { }
    record RejectInvoiceRequest(@NotBlank @Size(max = 500) String reason) { }
    record CreditInvoiceRequest(
            @NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String creditNoteUrl,
            @NotBlank @Size(max = 500) String reason) { }
    record MerchantChannelRequest(@NotBlank String channel, @NotBlank @Size(max = 128) String merchantId,
                                  @NotBlank @Size(max = 128) String applicationId,
                                  @NotBlank @Pattern(regexp = "env:[A-Z][A-Z0-9_]{1,80}") String secretReference,
                                  @NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String notifyUrl,
                                  @NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String refundNotifyUrl,
                                  @NotBlank String status) { }
    record UpdateMerchantChannelRequest(
            @NotBlank @Pattern(regexp = "env:[A-Z][A-Z0-9_]{1,80}") String secretReference,
            @NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String notifyUrl,
            @NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String refundNotifyUrl,
            @NotBlank String status, @Min(0) long version) { }
    record WalletAdjustmentRequest(@NotNull UUID customerId, @NotBlank String direction,
                                   @Min(1) long amountMinor, @NotBlank @Size(max = 500) String reason) { }
    record PaymentForRefund(UUID id, UUID orderId, String channel, String providerTransactionNo,
                            long amountMinor, String currency) { }
    record RefundSeed(UUID id, UUID paymentId, UUID orderId, String merchantRefundNo, String channel,
                      String providerTransactionNo, long amountMinor, long originalPaymentAmountMinor,
                      String currency, String reason) { }
    record PaymentMatch(UUID id, long amountMinor, String providerTransactionNo) { }
    record Rule(UUID organizationId, int shareBasisPoints, int platformServiceFeeBasisPoints,
                long fixedServiceFeeMinor, int hierarchyLevel) { }
    record WalletBalance(UUID id, long balanceMinor) { }
    record MerchantChannelView(UUID id, String channel, String merchantId, String applicationId,
                               String secretReference, String notifyUrl, String refundNotifyUrl,
                               String status, long version) { }
    record PaymentView(UUID id, UUID orderId, String orderNo, String channel, String transactionType,
                       String merchantOrderNo, String providerTransactionNo, long amountMinor,
                       String currency, String status, Instant createdAt, Instant completedAt) { }
    record RefundView(UUID id, UUID paymentId, String merchantRefundNo, String providerRefundNo,
                      long amountMinor, String status, String reason, Instant createdAt, Instant completedAt) { }
    record ReconciliationResult(UUID batchId, String status, int totalCount, int matchedCount, int exceptionCount) { }
    record SettlementRuleView(UUID id, UUID organizationId, String organizationName, String name,
                              String beneficiaryCode, int shareBasisPoints,
                              int platformServiceFeeBasisPoints, long fixedServiceFeeMinor,
                              LocalDate effectiveFrom, LocalDate effectiveUntil, String status) { }
    record SettlementView(UUID id, UUID ruleId, LocalDate periodStart, LocalDate periodEnd,
                          long grossAmountMinor, long platformServiceFeeMinor,
                          long settlementAmountMinor, String status) { }
    record InvoiceView(UUID id, UUID orderId, String title, String taxNumber, @Email String email,
                       long amountMinor, String status, String invoiceUrl, Instant createdAt) { }
}
