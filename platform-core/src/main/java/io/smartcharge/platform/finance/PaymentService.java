package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
final class PaymentService {
    private static final Set<String> CHANNELS = Set.of("WECHAT", "ALIPAY");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final CurrentCustomer currentCustomer;
    private final PaymentGatewayRegistry gateways;
    private final AuditService audit;
    private final ProfitSharingPlanner profitSharing;
    private final int intentExpireMinutes;
    private final boolean wechatEnabled;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    PaymentService(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, CurrentCustomer currentCustomer,
                   PaymentGatewayRegistry gateways, AuditService audit, ProfitSharingPlanner profitSharing,
                   @Value("${charging.payment.intent-expire-minutes:30}") int intentExpireMinutes,
                   @Value("${payments.wechat-enabled:false}") boolean wechatEnabled) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.currentCustomer = currentCustomer;
        this.gateways = gateways;
        this.audit = audit;
        this.profitSharing = profitSharing;
        if (intentExpireMinutes < 2 || intentExpireMinutes > 120) {
            throw new IllegalArgumentException("Payment intent expiry must be between 2 and 120 minutes");
        }
        this.intentExpireMinutes = intentExpireMinutes;
        this.wechatEnabled = wechatEnabled;
    }

    PaymentIntent create(String idempotencyKey, UUID orderId, String channel) {
        validateKey(idempotencyKey);
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unsupported payment channel");
        if ("WECHAT".equals(channel) && !wechatEnabled) {
            throw new DomainException("WeChat payment is disabled; complete merchant configuration before enabling new payments");
        }
        PaymentGateway gateway = gateways.required(channel);
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        PaymentRecord prepared = tenantJdbc.readWrite(() -> preparePayment(
                tenantId, customerId, orderId, channel, idempotencyKey));
        if (Set.of("CREATED", "PROCESSING").contains(prepared.status())
                && (expired(closingDeadline(prepared.createdAt(), prepared.expiresAt()))
                    || (!prepared.exactRequest() && prepared.clientParameters() == null))) {
            try {
                applyProviderStatus(tenantId, prepared.id(), resolveProviderStatus(tenantId, prepared.merchantChannelId(),
                        prepared.channel(), prepared.merchantOrderNo(), closingDeadline(prepared.createdAt(), prepared.expiresAt())));
            } catch (ProviderResourceNotFoundException notCreated) {
                recoverMissingPayment(tenantId, prepared.id());
            }
            UUID paymentId = prepared.id();
            prepared = tenantJdbc.readWrite(() -> lockCustomerPayment(tenantId, customerId, paymentId));
            if (Set.of("CREATED", "PROCESSING").contains(prepared.status())) {
                if (!prepared.exactRequest()) {
                    throw new DomainException("Legacy payment has no immutable provider request; finish the existing payment or wait for official closure");
                }
                throw new DomainException("Expired payment is still being confirmed; retry after provider reconciliation");
            }
        }
        PaymentRecord payment = prepared;
        if (Set.of("SUCCEEDED", "FAILED", "CLOSED").contains(payment.status())) {
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), payment.channel(), payment.amountMinor(),
                    payment.status(), Map.of());
        }
        if ("PROCESSING".equals(payment.status()) && payment.clientParameters() != null) {
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), payment.channel(), payment.amountMinor(),
                    payment.status(), payment.clientParameters());
        }
        try {
            PaymentGateway.GatewayIntent intent = gateway.createPayment(gatewayRequest(tenantId, payment));
            String response = toJson(intent.clientParameters());
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update payment_transaction set status='PROCESSING', response_payload=cast(? as jsonb),
                               updated_at=now() where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                        """, response, tenantId, payment.id());
                return null;
            });
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), channel, payment.amountMinor(),
                    "PROCESSING", intent.clientParameters());
        } catch (RuntimeException failure) {
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update payment_transaction set status='PROCESSING', updated_at=now(), response_payload=null
                         where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                        """, tenantId, payment.id());
                if (failure instanceof ProviderRequestRejectedException rejected) {
                    audit.record("PAYMENT_PROVIDER_REJECTED", "payment_transaction", payment.id(), null,
                            Map.of("providerCode", rejected.code(), "merchantOrderNo", payment.merchantOrderNo()));
                }
                return null;
            });
            if (failure instanceof ProviderRequestRejectedException rejected) {
                throw new DomainException("Payment provider rejected request: " + rejected.code()
                        + "; correct the merchant configuration and retry the same payment");
            }
            throw failure;
        }
    }

    void processCallback(String channel, String tenantCode, String merchantId,
                         Map<String, String> headers, String body) {
        UUID callbackTenant = resolveTenant(tenantCode);
        UUID merchantChannelId = resolveMerchantChannel(callbackTenant, channel, merchantId);
        PaymentGateway.VerifiedCallback callback = gateways.required(channel)
                .verifyCallback(callbackTenant, merchantChannelId, headers, body);
        Route route = jdbc.query("select tenant_id, payment_id from payment_route where merchant_order_no=?",
                (result, row) -> new Route(result.getObject("tenant_id", UUID.class),
                        result.getObject("payment_id", UUID.class)), callback.merchantOrderNo()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown merchant order"));
        if (!route.tenantId().equals(callbackTenant)) throw new DomainException("Payment callback tenant mismatch");
        tenantJdbc.readWriteAs(route.tenantId(), () -> {
            PaymentRecord payment = lockPayment(route.tenantId(), route.paymentId());
            if (!merchantChannelId.equals(payment.merchantChannelId())) {
                throw new DomainException("Payment callback merchant route mismatch");
            }
            int inserted = jdbc.update("""
                    insert into payment_webhook
                        (id, tenant_id, payment_id, channel, provider_event_id, signature_valid, payload, processed_at)
                    values (?, ?, ?, ?, ?, true, cast(? as jsonb), now()) on conflict do nothing
                    """, UUID.randomUUID(), route.tenantId(), payment.id(), channel,
                    callback.providerEventId(), callback.rawPayload());
            if (inserted == 1 && callback.succeeded()) {
                complete(route.tenantId(), payment, callback.providerTransactionNo(), callback.amountMinor(),
                        callback.completedAt(), callback.providerEventId());
            }
            return null;
        });
    }

    void applyProviderStatus(UUID tenantId, UUID paymentId, PaymentGateway.GatewayPaymentStatus status) {
        tenantJdbc.readWriteAs(tenantId, () -> {
            PaymentRecord payment = lockPayment(tenantId, paymentId);
            if (status.state() == PaymentGateway.ProviderState.SUCCEEDED) {
                complete(tenantId, payment, status.providerTransactionNo(), status.amountMinor(),
                        status.completedAt(), "provider-query:" + payment.merchantOrderNo());
            } else if (status.state() == PaymentGateway.ProviderState.FAILED
                    && Set.of("CREATED", "PROCESSING").contains(payment.status())) {
                jdbc.update("""
                        update payment_transaction set status='FAILED', updated_at=now()
                         where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                        """, tenantId, paymentId);
                audit.record("PAYMENT_FAILED", "payment_transaction", paymentId, null,
                        Map.of("source", "PROVIDER_QUERY"));
            } else if (status.state() == PaymentGateway.ProviderState.PENDING) {
                jdbc.update("""
                        update payment_transaction set updated_at=now()
                         where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                        """, tenantId, paymentId);
            }
            return null;
        });
    }

    PaymentGateway.GatewayPaymentStatus resolveProviderStatus(UUID tenantId, UUID merchantChannelId, String channel,
                                                              String merchantOrderNo, Instant closeAfter) {
        PaymentGateway gateway = gateways.required(channel);
        PaymentGateway.GatewayPaymentStatus status = gateway.queryPayment(tenantId, merchantChannelId, merchantOrderNo);
        if (status.state() != PaymentGateway.ProviderState.PENDING || !expired(closeAfter)) return status;
        try {
            gateway.closePayment(tenantId, merchantChannelId, merchantOrderNo);
            return new PaymentGateway.GatewayPaymentStatus(null, 0, PaymentGateway.ProviderState.FAILED, null);
        } catch (RuntimeException closeFailure) {
            // A payment may have won the race with close; only the provider's next answer can resolve it.
            return gateway.queryPayment(tenantId, merchantChannelId, merchantOrderNo);
        }
    }

    void recoverMissingPayment(UUID tenantId, UUID paymentId) {
        PaymentRecord payment = tenantJdbc.readWriteAs(tenantId, () -> lockPayment(tenantId, paymentId));
        if (!Set.of("CREATED", "PROCESSING").contains(payment.status())) return;
        if (!payment.exactRequest()) {
            throw new DomainException("Legacy payment has no immutable provider request; official closure or manual reconciliation is required");
        }
        if (expired(payment.expiresAt())) {
            if (!missingPaymentCanBeReleased(payment.expiresAt(), Instant.now())) {
                throw new DomainException("Expired payment is still inside the provider confirmation safety window");
            }
            applyProviderStatus(tenantId, payment.id(), new PaymentGateway.GatewayPaymentStatus(
                    null, 0, PaymentGateway.ProviderState.FAILED, null));
            return;
        }
        try {
            PaymentGateway.GatewayIntent intent = gateways.required(payment.channel()).createPayment(gatewayRequest(tenantId, payment));
            tenantJdbc.readWriteAs(tenantId, () -> jdbc.update("""
                    update payment_transaction set status='PROCESSING', response_payload=cast(? as jsonb), updated_at=now()
                     where tenant_id=? and id=? and status in ('CREATED','PROCESSING')
                    """, toJson(intent.clientParameters()), tenantId, payment.id()));
        } catch (ProviderRequestRejectedException rejected) {
            tenantJdbc.readWriteAs(tenantId, () -> {
                audit.record("PAYMENT_PROVIDER_REJECTED", "payment_transaction", payment.id(), null,
                        Map.of("providerCode", rejected.code(), "source", "RECOVERY"));
                return null;
            });
            throw rejected;
        }
    }

    private PaymentGateway.GatewayPayment gatewayRequest(UUID tenantId, PaymentRecord payment) {
        requireSubmissionWindow(payment.expiresAt(), Instant.now());
        return new PaymentGateway.GatewayPayment(tenantId, payment.merchantChannelId(), payment.id(),
                payment.merchantOrderNo(), payment.amountMinor(), payment.currency(), "Charging order " + payment.orderNo(),
                payment.payerSubject(), payment.profitSharing(), payment.expiresAt());
    }

    private boolean expired(Instant expiresAt) {
        return !expiresAt.isAfter(Instant.now());
    }

    Instant closingDeadline(Instant createdAt, Instant storedExpiry) {
        // Legacy local timeout is only a policy for attempting official closure, never proof of provider expiry.
        return storedExpiry == null ? createdAt.plusSeconds(intentExpireMinutes * 60L) : storedExpiry;
    }

    static Instant paymentExpiry(Instant createdAt, String storedExpiry) {
        // prepay_id's two-hour lifetime is not the order's payment window. Missing original deadlines stay unknown.
        if (storedExpiry == null || storedExpiry.isBlank()) return null;
        try {
            return Instant.parse(storedExpiry);
        } catch (java.time.format.DateTimeParseException invalid) {
            throw new DomainException("Stored payment expiry is invalid; provider reconciliation is required");
        }
    }

    static void requireSubmissionWindow(Instant expiresAt, Instant now) {
        if (expiresAt == null || !expiresAt.isAfter(now.plusSeconds(60))) {
            throw new DomainException("Payment has less than one minute remaining; no new provider request will be submitted");
        }
    }

    static boolean missingPaymentCanBeReleased(Instant expiresAt, Instant now) {
        return expiresAt != null && !expiresAt.plusSeconds(60).isAfter(now);
    }

    private PaymentRecord preparePayment(UUID tenantId, UUID customerId, UUID orderId,
                                         String channel, String idempotencyKey) {
        String requestHash = sha256(orderId + ":" + channel);
        Idempotency existing = jdbc.query("""
                select request_hash, response_body ->> 'paymentId' as payment_id
                  from idempotency_record where tenant_id=? and scope='PAYMENT_CREATE' and idempotency_key=?
                """, (result, row) -> new Idempotency(result.getString("request_hash"),
                    UUID.fromString(result.getString("payment_id"))), tenantId, idempotencyKey).stream().findFirst().orElse(null);
        if (existing != null) {
            if (!existing.requestHash().equals(requestHash)) throw new DomainException("Idempotency key was reused for a different request");
            return lockCustomerPayment(tenantId, customerId, existing.paymentId());
        }
        OrderToPay order = jdbc.query("""
                select o.order_no, o.payable_amount_minor, o.paid_amount_minor, o.currency, o.status,
                       s.organization_id
                  from charging_order o
                  join connector c on c.tenant_id=o.tenant_id and c.id=o.connector_id
                  join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                  join station s on s.tenant_id=d.tenant_id and s.id=d.station_id
                 where o.tenant_id=? and o.customer_id=? and o.id=? for update of o
                """, (result, row) -> new OrderToPay(
                    result.getString("order_no"), result.getLong("payable_amount_minor"),
                    result.getLong("paid_amount_minor"), result.getString("currency"), result.getString("status"),
                    result.getObject("organization_id", UUID.class)),
                tenantId, customerId, orderId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Order does not exist"));
        if (!"COMPLETED".equals(order.status())) throw new DomainException("Order can only be paid after charging completes");
        Long alreadyRefunded = jdbc.queryForObject("""
                select coalesce(sum(r.amount_minor),0) from refund_transaction r
                  join payment_transaction p on p.tenant_id=r.tenant_id and p.id=r.payment_id
                 where p.tenant_id=? and p.order_id=? and r.status='SUCCEEDED'
                """, Long.class, tenantId, orderId);
        long amount = order.payableAmountMinor() - order.paidAmountMinor() - (alreadyRefunded == null ? 0 : alreadyRefunded);
        if (amount <= 0) throw new DomainException("Order has no outstanding balance");
        if ("WECHAT".equals(channel) && amount > Integer.MAX_VALUE) {
            throw new DomainException("Payment amount exceeds channel limit");
        }
        MerchantRoute merchantRoute = jdbc.query("""
                select id, profit_sharing_required from merchant_channel
                 where tenant_id=? and organization_id=? and channel=? and status='ACTIVE'
                """, (result, row) -> new MerchantRoute(
                result.getObject("id", UUID.class), result.getBoolean("profit_sharing_required")),
                tenantId, order.organizationId(), channel).stream().findFirst()
                .orElseThrow(() -> new DomainException(
                        "The station organization has no active direct collecting merchant channel"));
        PaymentRecord active = jdbc.query("""
                select p.id, p.order_id, o.order_no, p.merchant_order_no, p.channel, p.amount_minor, p.currency,
                       p.status, p.created_at, p.merchant_channel_id, p.profit_sharing_required, p.response_payload::text,
                       p.request_payload->>'paymentExpiresAt' payment_expires_at,
                       p.request_payload->>'payerSubject' stored_payer_subject,
                       (select ci.provider_subject from customer_identity ci
                         where ci.tenant_id=p.tenant_id and ci.customer_id=o.customer_id and ci.provider=p.channel
                         order by ci.created_at limit 1) payer_subject
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and p.order_id=? and p.transaction_type='PAY'
                   and p.status in ('CREATED','PROCESSING')
                """, (result, row) -> payment(result), tenantId, orderId).stream().findFirst().orElse(null);
        if (active != null) {
            if (!active.channel().equals(channel)) {
                throw new DomainException("Another payment channel is already processing for this order");
            }
            saveIdempotency(tenantId, idempotencyKey, requestHash, active.id());
            return active;
        }
        UUID paymentId = UUID.randomUUID();
        String merchantOrderNo = "P" + Instant.now().toEpochMilli() + paymentId.toString().replace("-", "").substring(0, 10);
        String payerSubject = jdbc.query("""
                select provider_subject from customer_identity
                 where tenant_id=? and customer_id=? and provider=? order by created_at limit 1
                """, (result, row) -> result.getString(1), tenantId, customerId, channel)
                .stream().findFirst().orElse(null);
        if (payerSubject == null || payerSubject.isBlank()) {
            throw new DomainException("Customer has no payment identity for this channel");
        }
        Instant createdAt = jdbc.queryForObject("select transaction_timestamp()", java.sql.Timestamp.class).toInstant();
        Instant expiresAt = createdAt.plusSeconds(intentExpireMinutes * 60L);
        jdbc.update("""
                insert into payment_transaction
                    (id, tenant_id, order_id, merchant_channel_id, channel, transaction_type, merchant_order_no,
                     amount_minor, currency, status, request_payload)
                values (?, ?, ?, ?, ?, 'PAY', ?, ?, ?, 'CREATED', cast(? as jsonb))
                """, paymentId, tenantId, orderId, merchantRoute.id(), channel,
                merchantOrderNo, amount, order.currency(),
                toJson(Map.of("idempotencyKeyHash", sha256(idempotencyKey), "payerSubject", payerSubject,
                        "paymentExpiresAt", expiresAt.toString())));
        boolean profitSharingRequired = profitSharing.createPlan(tenantId, merchantRoute.id(), paymentId,
                order.organizationId(), channel, amount, merchantRoute.profitSharingRequired());
        if (profitSharingRequired) {
            jdbc.update("""
                    update payment_transaction set profit_sharing_required=true
                     where tenant_id=? and id=?
                    """, tenantId, paymentId);
        }
        jdbc.update("insert into payment_route (merchant_order_no, tenant_id, payment_id) values (?, ?, ?)",
                merchantOrderNo, tenantId, paymentId);
        saveIdempotency(tenantId, idempotencyKey, requestHash, paymentId);
        audit.record("PAYMENT_CREATED", "payment_transaction", paymentId, null,
                Map.of("orderId", orderId, "channel", channel, "amountMinor", amount));
        return new PaymentRecord(paymentId, orderId, order.orderNo(), merchantOrderNo, channel,
                amount, order.currency(), "CREATED", payerSubject, merchantRoute.id(),
                profitSharingRequired, null, createdAt, expiresAt, true);
    }

    private void saveIdempotency(UUID tenantId, String key, String requestHash, UUID paymentId) {
        int inserted = jdbc.update("""
                insert into idempotency_record
                    (id, tenant_id, scope, idempotency_key, request_hash, response_status, response_body, expires_at)
                values (?, ?, 'PAYMENT_CREATE', ?, ?, 202, jsonb_build_object('paymentId', ?), now()+interval '24 hours')
                on conflict (tenant_id, scope, idempotency_key) do nothing
                """, UUID.randomUUID(), tenantId, key, requestHash, paymentId.toString());
        if (inserted == 0) {
            Idempotency existing = jdbc.query("""
                    select request_hash, response_body ->> 'paymentId' as payment_id
                      from idempotency_record
                     where tenant_id=? and scope='PAYMENT_CREATE' and idempotency_key=?
                    """, (result, row) -> new Idempotency(result.getString("request_hash"),
                    UUID.fromString(result.getString("payment_id"))), tenantId, key).getFirst();
            if (!existing.requestHash().equals(requestHash) || !existing.paymentId().equals(paymentId)) {
                throw new DomainException("Idempotency key was reused for a different request");
            }
        }
    }

    private void complete(UUID tenantId, PaymentRecord payment, String providerTransactionNo,
                          long amountMinor, Instant completedAt, String eventId) {
        if ("SUCCEEDED".equals(payment.status())) return;
        if (amountMinor != payment.amountMinor()) throw new DomainException("Provider payment amount does not match");
        if (providerTransactionNo == null || providerTransactionNo.isBlank() || completedAt == null) {
            throw new DomainException("Provider payment success evidence is incomplete");
        }
        int changed = jdbc.update("""
                update payment_transaction set status='SUCCEEDED', provider_transaction_no=?, completed_at=?,
                                               updated_at=now()
                 where tenant_id=? and id=? and status in ('CREATED','PROCESSING','FAILED')
                """, providerTransactionNo, JdbcTimes.timestamp(completedAt), tenantId, payment.id());
        if (changed != 1) throw new DomainException("Payment cannot enter succeeded state");
        int orderChanged = jdbc.update("""
                update charging_order set paid_amount_minor=paid_amount_minor+?, updated_at=now(), version=version+1
                 where tenant_id=? and id=? and paid_amount_minor+? <= payable_amount_minor
                """, amountMinor, tenantId, payment.orderId(), amountMinor);
        if (orderChanged != 1) throw new DomainException("Payment would exceed the order balance");
        postLedger(tenantId, payment, eventId, completedAt);
        jdbc.update("""
                insert into notification_outbox
                    (id, tenant_id, channel, template_code, recipient, payload, status)
                values (?, ?, ?, 'PAYMENT_SUCCEEDED', ?,
                        jsonb_build_object('orderId', ?, 'amountMinor', ?), 'PENDING')
                """, UUID.randomUUID(), tenantId, payment.channel(), payment.payerSubject(),
                payment.orderId().toString(), amountMinor);
        audit.record("PAYMENT_SUCCEEDED", "payment_transaction", payment.id(), null,
                Map.of("providerTransactionNo", providerTransactionNo, "amountMinor", amountMinor));
    }

    private void postLedger(UUID tenantId, PaymentRecord payment, String reference, Instant completedAt) {
        UUID cash = ensureLedgerAccount(tenantId, "CASH:" + payment.channel(), "ASSET", payment.currency());
        UUID revenue = ensureLedgerAccount(tenantId, "CHARGING_REVENUE", "REVENUE", payment.currency());
        UUID transactionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into ledger_transaction
                    (id, tenant_id, reference_type, reference_id, description, occurred_at)
                values (?, ?, 'PAYMENT', ?, ?, ?) on conflict do nothing
                """, transactionId, tenantId, payment.id(), "Payment " + reference, JdbcTimes.timestamp(completedAt));
        if (inserted == 0) return;
        jdbc.update("""
                insert into ledger_entry (id, tenant_id, transaction_id, account_id, direction, amount_minor, currency)
                values (?, ?, ?, ?, 'DEBIT', ?, ?), (?, ?, ?, ?, 'CREDIT', ?, ?)
                """, UUID.randomUUID(), tenantId, transactionId, cash, payment.amountMinor(), payment.currency(),
                UUID.randomUUID(), tenantId, transactionId, revenue, payment.amountMinor(), payment.currency());
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
                    return jdbc.queryForObject("select id from ledger_account where tenant_id=? and account_code=? and currency=?",
                            UUID.class, tenantId, code, currency);
                });
    }

    private PaymentRecord lockCustomerPayment(UUID tenantId, UUID customerId, UUID paymentId) {
        return jdbc.query("""
                select p.id, p.order_id, o.order_no, p.merchant_order_no, p.channel, p.amount_minor, p.currency, p.status, p.created_at,
                       p.merchant_channel_id, p.profit_sharing_required, p.response_payload::text,
                       p.request_payload->>'paymentExpiresAt' payment_expires_at,
                       p.request_payload->>'payerSubject' stored_payer_subject,
                       (select ci.provider_subject from customer_identity ci
                         where ci.tenant_id=p.tenant_id and ci.customer_id=o.customer_id and ci.provider=p.channel
                         order by ci.created_at limit 1) payer_subject
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and o.customer_id=? and p.id=? for update of p
                """, (result, row) -> payment(result), tenantId, customerId, paymentId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Payment does not exist"));
    }

    private PaymentRecord lockPayment(UUID tenantId, UUID paymentId) {
        return jdbc.query("""
                select p.id, p.order_id, o.order_no, p.merchant_order_no, p.channel, p.amount_minor, p.currency, p.status, p.created_at,
                       p.merchant_channel_id, p.profit_sharing_required, p.response_payload::text,
                       p.request_payload->>'paymentExpiresAt' payment_expires_at,
                       p.request_payload->>'payerSubject' stored_payer_subject,
                       (select ci.provider_subject from customer_identity ci
                         where ci.tenant_id=p.tenant_id and ci.customer_id=o.customer_id and ci.provider=p.channel
                         order by ci.created_at limit 1) payer_subject
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and p.id=? for update of p
                """, (result, row) -> payment(result), tenantId, paymentId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Payment does not exist"));
    }

    private static PaymentRecord payment(java.sql.ResultSet result) throws java.sql.SQLException {
        String storedPayer = result.getString("stored_payer_subject"), storedExpiry = result.getString("payment_expires_at");
        Instant createdAt = result.getTimestamp("created_at").toInstant();
        return new PaymentRecord(result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                result.getString("order_no"), result.getString("merchant_order_no"), result.getString("channel"),
                result.getLong("amount_minor"), result.getString("currency"), result.getString("status"),
                storedPayer == null ? result.getString("payer_subject") : storedPayer, result.getObject("merchant_channel_id", UUID.class),
                result.getBoolean("profit_sharing_required"),
                clientParameters(result.getString("response_payload")), createdAt, paymentExpiry(createdAt, storedExpiry),
                storedPayer != null && !storedPayer.isBlank() && storedExpiry != null && !storedExpiry.isBlank());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> clientParameters(String payload) {
        if (payload == null || payload.isBlank()) return null;
        try {
            Map<String, Object> values = JsonMapper.builder().build().readValue(payload, LinkedHashMap.class);
            Map<String, String> strings = new LinkedHashMap<>();
            values.forEach((key, value) -> strings.put(key, value == null ? "" : value.toString()));
            return Map.copyOf(strings);
        } catch (Exception invalid) {
            return null;
        }
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

    private String toJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalArgumentException("Payment data cannot be serialized", error); }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 8-128 safe characters");
        }
    }

    record Idempotency(String requestHash, UUID paymentId) { }
    record OrderToPay(String orderNo, long payableAmountMinor, long paidAmountMinor, String currency, String status,
                      UUID organizationId) { }
    record PaymentRecord(UUID id, UUID orderId, String orderNo, String merchantOrderNo, String channel,
                         long amountMinor, String currency, String status, String payerSubject,
                         UUID merchantChannelId,
                         boolean profitSharing,
                         Map<String, String> clientParameters, Instant createdAt, Instant expiresAt, boolean exactRequest) { }
    record MerchantRoute(UUID id, boolean profitSharingRequired) { }
    record Route(UUID tenantId, UUID paymentId) { }
    record PaymentIntent(UUID paymentId, String merchantOrderNo, String channel, long amountMinor,
                         String status, Map<String, String> clientParameters) { }
}
