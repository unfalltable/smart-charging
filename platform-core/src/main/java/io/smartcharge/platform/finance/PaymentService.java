package io.smartcharge.platform.finance;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.domain.DomainException;
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
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    PaymentService(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, CurrentCustomer currentCustomer,
                   PaymentGatewayRegistry gateways, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.currentCustomer = currentCustomer;
        this.gateways = gateways;
        this.audit = audit;
    }

    PaymentIntent create(String idempotencyKey, UUID orderId, String channel) {
        validateKey(idempotencyKey);
        if (!CHANNELS.contains(channel)) throw new IllegalArgumentException("Unsupported payment channel");
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        PaymentRecord payment = tenantJdbc.readWrite(() -> preparePayment(
                tenantId, customerId, orderId, channel, idempotencyKey));
        if ("SUCCEEDED".equals(payment.status())) {
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), payment.channel(), payment.amountMinor(),
                    payment.status(), Map.of());
        }
        if ("PROCESSING".equals(payment.status()) && payment.clientParameters() != null) {
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), payment.channel(), payment.amountMinor(),
                    payment.status(), payment.clientParameters());
        }
        PaymentGateway gateway = gateways.required(channel);
        try {
            PaymentGateway.GatewayIntent intent = gateway.createPayment(new PaymentGateway.GatewayPayment(
                    tenantId, payment.id(), payment.merchantOrderNo(), payment.amountMinor(), payment.currency(),
                    "Charging order " + payment.orderNo(), payment.payerSubject()));
            String response = toJson(intent.clientParameters());
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update payment_transaction set status='PROCESSING', response_payload=cast(? as jsonb),
                               updated_at=now() where tenant_id=? and id=? and status in ('CREATED','FAILED')
                        """, response, tenantId, payment.id());
                return null;
            });
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), channel, payment.amountMinor(),
                    "PROCESSING", intent.clientParameters());
        } catch (RuntimeException failure) {
            tenantJdbc.readWrite(() -> {
                jdbc.update("""
                        update payment_transaction set status='PROCESSING', updated_at=now(), response_payload=null
                         where tenant_id=? and id=? and status <> 'SUCCEEDED'
                        """, tenantId, payment.id());
                return null;
            });
            throw failure;
        }
    }

    PaymentIntent completeLocal(UUID paymentId) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> {
            PaymentRecord payment = lockCustomerPayment(tenantId, customerId, paymentId);
            complete(tenantId, payment, "local-" + payment.id(), payment.amountMinor(), "local-simulation");
            return new PaymentIntent(payment.id(), payment.merchantOrderNo(), payment.channel(),
                    payment.amountMinor(), "SUCCEEDED", Map.of());
        });
    }

    void processCallback(String channel, String tenantCode, Map<String, String> headers, String body) {
        UUID callbackTenant = resolveTenant(tenantCode);
        PaymentGateway.VerifiedCallback callback = gateways.required(channel)
                .verifyCallback(callbackTenant, headers, body);
        Route route = jdbc.query("select tenant_id, payment_id from payment_route where merchant_order_no=?",
                (result, row) -> new Route(result.getObject("tenant_id", UUID.class),
                        result.getObject("payment_id", UUID.class)), callback.merchantOrderNo()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown merchant order"));
        if (!route.tenantId().equals(callbackTenant)) throw new DomainException("Payment callback tenant mismatch");
        tenantJdbc.readWriteAs(route.tenantId(), () -> {
            PaymentRecord payment = lockPayment(route.tenantId(), route.paymentId());
            int inserted = jdbc.update("""
                    insert into payment_webhook
                        (id, tenant_id, payment_id, channel, provider_event_id, signature_valid, payload, processed_at)
                    values (?, ?, ?, ?, ?, true, cast(? as jsonb), now()) on conflict do nothing
                    """, UUID.randomUUID(), route.tenantId(), payment.id(), channel,
                    callback.providerEventId(), callback.rawPayload());
            if (inserted == 1 && callback.succeeded()) {
                complete(route.tenantId(), payment, callback.providerTransactionNo(), callback.amountMinor(),
                        callback.providerEventId());
            }
            return null;
        });
    }

    void applyProviderStatus(UUID tenantId, UUID paymentId, PaymentGateway.GatewayPaymentStatus status) {
        tenantJdbc.readWriteAs(tenantId, () -> {
            PaymentRecord payment = lockPayment(tenantId, paymentId);
            if (status.state() == PaymentGateway.ProviderState.SUCCEEDED) {
                complete(tenantId, payment, status.providerTransactionNo(), status.amountMinor(),
                        "provider-query:" + payment.merchantOrderNo());
            } else if (status.state() == PaymentGateway.ProviderState.FAILED
                    && "PROCESSING".equals(payment.status())) {
                jdbc.update("""
                        update payment_transaction set status='FAILED', updated_at=now()
                         where tenant_id=? and id=? and status='PROCESSING'
                        """, tenantId, paymentId);
                audit.record("PAYMENT_FAILED", "payment_transaction", paymentId, null,
                        Map.of("source", "PROVIDER_QUERY"));
            }
            return null;
        });
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
                select order_no, payable_amount_minor, paid_amount_minor, currency, status
                  from charging_order where tenant_id=? and customer_id=? and id=? for update
                """, (result, row) -> new OrderToPay(
                    result.getString("order_no"), result.getLong("payable_amount_minor"),
                    result.getLong("paid_amount_minor"), result.getString("currency"), result.getString("status")),
                tenantId, customerId, orderId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Order does not exist"));
        if (!"COMPLETED".equals(order.status())) throw new DomainException("Order can only be paid after charging completes");
        long amount = order.payableAmountMinor() - order.paidAmountMinor();
        if (amount <= 0) throw new DomainException("Order has no outstanding balance");
        PaymentRecord active = jdbc.query("""
                select p.id, p.order_id, o.order_no, p.merchant_order_no, p.channel, p.amount_minor, p.currency,
                       p.status, p.response_payload::text,
                       (select ci.provider_subject from customer_identity ci
                         where ci.tenant_id=p.tenant_id and ci.customer_id=o.customer_id and ci.provider=p.channel
                         order by ci.created_at limit 1) payer_subject
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and p.order_id=? and p.transaction_type='PAY'
                   and p.status in ('CREATED','PROCESSING') for update of p
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
        jdbc.update("""
                insert into payment_transaction
                    (id, tenant_id, order_id, channel, transaction_type, merchant_order_no,
                     amount_minor, currency, status, request_payload)
                values (?, ?, ?, ?, 'PAY', ?, ?, ?, 'CREATED', cast(? as jsonb))
                """, paymentId, tenantId, orderId, channel, merchantOrderNo, amount, order.currency(),
                toJson(Map.of("idempotencyKeyHash", sha256(idempotencyKey))));
        jdbc.update("insert into payment_route (merchant_order_no, tenant_id, payment_id) values (?, ?, ?)",
                merchantOrderNo, tenantId, paymentId);
        saveIdempotency(tenantId, idempotencyKey, requestHash, paymentId);
        audit.record("PAYMENT_CREATED", "payment_transaction", paymentId, null,
                Map.of("orderId", orderId, "channel", channel, "amountMinor", amount));
        String payerSubject = jdbc.query("""
                select provider_subject from customer_identity
                 where tenant_id=? and customer_id=? and provider=? order by created_at limit 1
                """, (result, row) -> result.getString(1), tenantId, customerId, channel)
                .stream().findFirst().orElse(null);
        return new PaymentRecord(paymentId, orderId, order.orderNo(), merchantOrderNo, channel,
                amount, order.currency(), "CREATED", payerSubject, null);
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
                          long amountMinor, String eventId) {
        if ("SUCCEEDED".equals(payment.status())) return;
        if (amountMinor != payment.amountMinor()) throw new DomainException("Provider payment amount does not match");
        int changed = jdbc.update("""
                update payment_transaction set status='SUCCEEDED', provider_transaction_no=?, completed_at=now(),
                                               updated_at=now()
                 where tenant_id=? and id=? and status in ('CREATED','PROCESSING','FAILED')
                """, providerTransactionNo, tenantId, payment.id());
        if (changed != 1) throw new DomainException("Payment cannot enter succeeded state");
        int orderChanged = jdbc.update("""
                update charging_order set paid_amount_minor=paid_amount_minor+?, updated_at=now(), version=version+1
                 where tenant_id=? and id=? and paid_amount_minor+? <= payable_amount_minor
                """, amountMinor, tenantId, payment.orderId(), amountMinor);
        if (orderChanged != 1) throw new DomainException("Payment would exceed the order balance");
        postLedger(tenantId, payment, eventId);
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

    private void postLedger(UUID tenantId, PaymentRecord payment, String reference) {
        UUID cash = ensureLedgerAccount(tenantId, "CASH:" + payment.channel(), "ASSET", payment.currency());
        UUID revenue = ensureLedgerAccount(tenantId, "CHARGING_REVENUE", "REVENUE", payment.currency());
        UUID transactionId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into ledger_transaction
                    (id, tenant_id, reference_type, reference_id, description, occurred_at)
                values (?, ?, 'PAYMENT', ?, ?, now()) on conflict do nothing
                """, transactionId, tenantId, payment.id(), "Payment " + reference);
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
                select p.id, p.order_id, o.order_no, p.merchant_order_no, p.channel, p.amount_minor, p.currency, p.status,
                       p.response_payload::text,
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
                select p.id, p.order_id, o.order_no, p.merchant_order_no, p.channel, p.amount_minor, p.currency, p.status,
                       p.response_payload::text,
                       (select ci.provider_subject from customer_identity ci
                         where ci.tenant_id=p.tenant_id and ci.customer_id=o.customer_id and ci.provider=p.channel
                         order by ci.created_at limit 1) payer_subject
                  from payment_transaction p join charging_order o on o.tenant_id=p.tenant_id and o.id=p.order_id
                 where p.tenant_id=? and p.id=? for update of p
                """, (result, row) -> payment(result), tenantId, paymentId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Payment does not exist"));
    }

    private static PaymentRecord payment(java.sql.ResultSet result) throws java.sql.SQLException {
        return new PaymentRecord(result.getObject("id", UUID.class), result.getObject("order_id", UUID.class),
                result.getString("order_no"), result.getString("merchant_order_no"), result.getString("channel"),
                result.getLong("amount_minor"), result.getString("currency"), result.getString("status"),
                result.getString("payer_subject"), clientParameters(result.getString("response_payload")));
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
        return jdbc.query("select id from tenant where code=? and status='ACTIVE'",
                (result, row) -> result.getObject(1, UUID.class), tenantCode).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown tenant callback route"));
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
    record OrderToPay(String orderNo, long payableAmountMinor, long paidAmountMinor, String currency, String status) { }
    record PaymentRecord(UUID id, UUID orderId, String orderNo, String merchantOrderNo, String channel,
                         long amountMinor, String currency, String status, String payerSubject,
                         Map<String, String> clientParameters) { }
    record Route(UUID tenantId, UUID paymentId) { }
    record PaymentIntent(UUID paymentId, String merchantOrderNo, String channel, long amountMinor,
                         String status, Map<String, String> clientParameters) { }
}
