package io.smartcharge.platform.messaging;

import io.nats.client.JetStream;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.smartcharge.platform.contracts.DeviceEnvelope;
import io.smartcharge.platform.billing.TariffCalculator;
import io.smartcharge.platform.billing.TariffCalculator.Mode;
import io.smartcharge.platform.billing.TariffCalculator.PriceRule;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
final class DeviceEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(DeviceEventConsumer.class);
    private static final Set<String> CONNECTOR_STATES = Set.of(
            "AVAILABLE", "RESERVED", "CHARGING", "FAULTED", "DISABLED", "OFFLINE");
    private final JetStream jetStream;
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final TariffCalculator tariffCalculator;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();
    private JetStreamSubscription subscription;

    DeviceEventConsumer(JetStream jetStream, JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                        TariffCalculator tariffCalculator) {
        this.jetStream = jetStream;
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.tariffCalculator = tariffCalculator;
    }

    @PostConstruct
    void subscribe() throws Exception {
        subscription = jetStream.subscribe("charging.device.>", PullSubscribeOptions.builder()
                .stream("DEVICE_EVENTS")
                .durable("platform-core-device-events")
                .build());
    }

    @Scheduled(fixedDelayString = "${device-events.consumer-delay-ms:100}")
    void consume() {
        for (Message message : subscription.fetch(100, Duration.ofMillis(200))) {
            try {
                DeviceEnvelope envelope = json.readValue(
                        new String(message.getData(), StandardCharsets.UTF_8), DeviceEnvelope.class);
                process(envelope);
                message.ack();
            } catch (IllegalArgumentException unrecoverable) {
                log.warn("Discarding invalid device event: reason={}", unrecoverable.getClass().getSimpleName());
                message.term();
            } catch (Exception retryable) {
                Throwable rootCause = rootCause(retryable);
                log.warn("Device event processing failed: reason={}, detail={}",
                        retryable.getClass().getSimpleName(), safeDetail(rootCause));
                message.nakWithDelay(Duration.ofSeconds(5));
            }
        }
    }

    void process(DeviceEnvelope envelope) throws Exception {
        DeviceRoute route = jdbc.query("select tenant_id, device_id from device_route where device_code = ?",
                (result, row) -> new DeviceRoute(result.getObject("tenant_id", UUID.class),
                        result.getObject("device_id", UUID.class)), envelope.deviceCode()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown device route"));
        JsonNode payload = json.readTree(envelope.payload());
        tenantJdbc.readWriteAs(route.tenantId(), () -> {
            int inserted = jdbc.update("""
                    insert into device_message
                        (id, tenant_id, device_id, message_id, nonce, event_type, occurred_at, payload)
                    values (?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                    on conflict do nothing
                    """, UUID.randomUUID(), route.tenantId(), route.deviceId(), envelope.messageId(),
                    envelope.nonce(), envelope.eventType().name(), JdbcTimes.timestamp(envelope.occurredAt()),
                    envelope.payload());
            if (inserted == 0) return null;
            jdbc.update("update device set status = 'ONLINE', last_seen_at = now(), updated_at = now() where tenant_id = ? and id = ?",
                    route.tenantId(), route.deviceId());
            switch (envelope.eventType()) {
                case BOOT, HEARTBEAT -> { }
                case CONNECTOR_STATUS -> updateConnector(route, envelope.occurredAt(), payload);
                case COMMAND_ACK -> acknowledgeCommand(route, payload);
                case SESSION_STARTED -> startSession(route, envelope.occurredAt(), payload);
                case METER_SAMPLE -> recordMeter(route, envelope.occurredAt(), payload);
                case SESSION_STOPPED -> stopSession(route, envelope.occurredAt(), payload);
                case ALARM -> recordAlarm(route, envelope, payload);
            }
            return null;
        });
    }

    private void updateConnector(DeviceRoute route, Instant occurredAt, JsonNode payload) {
        int connectorNo = requiredInt(payload, "connectorNo");
        String status = requiredText(payload, "status");
        if (!CONNECTOR_STATES.contains(status)) throw new IllegalArgumentException("Invalid connector status");
        jdbc.update("""
                update connector set status = ?, last_status_at = ?, updated_at = now(), version = version + 1
                 where tenant_id = ? and device_id = ? and connector_no = ?
                   and (last_status_at is null or last_status_at <= ?)
                """, status, JdbcTimes.timestamp(occurredAt), route.tenantId(), route.deviceId(), connectorNo,
                JdbcTimes.timestamp(occurredAt));
    }

    private void acknowledgeCommand(DeviceRoute route, JsonNode payload) {
        UUID commandId = requiredUuid(payload, "commandId");
        boolean accepted = payload.path("accepted").asBoolean(false);
        jdbc.update("""
                update device_command
                   set status = ?, acknowledged_at = now(), failure_code = ?, updated_at = now(), version = version + 1
                 where tenant_id = ? and device_id = ? and id = ? and status in ('PENDING', 'PUBLISHED')
                """, accepted ? "ACKNOWLEDGED" : "FAILED",
                accepted ? null : textOrDefault(payload.path("failureCode"), "DEVICE_REJECTED"),
                route.tenantId(), route.deviceId(), commandId);
    }

    private void startSession(DeviceRoute route, Instant occurredAt, JsonNode payload) {
        UUID orderId = requiredUuid(payload, "orderId");
        long meterStartWh = requiredLong(payload, "meterStartWh");
        String currentStatus = orderStatus(route.tenantId(), route.deviceId(), orderId);
        if ("CHARGING".equals(currentStatus) || "STOP_PENDING".equals(currentStatus)
                || "COMPLETED".equals(currentStatus)) return;
        int changed = jdbc.update("""
                update charging_order set status = 'CHARGING', started_at = ?, updated_at = now(), version = version + 1
                 where tenant_id = ? and id = ? and status = 'START_PENDING'
                """, JdbcTimes.timestamp(occurredAt), route.tenantId(), orderId);
        if (changed != 1) throw new IllegalArgumentException("Order cannot enter charging state");
        recordOrderStatus(route.tenantId(), orderId, "START_PENDING", "CHARGING", "DEVICE_CONFIRMED_START",
                "device:" + route.deviceId());
        jdbc.update("""
                update charging_session set meter_start_wh = ?, started_at = ?, updated_at = now(), version = version + 1
                 where tenant_id = ? and order_id = ?
                """, meterStartWh, JdbcTimes.timestamp(occurredAt), route.tenantId(), orderId);
        jdbc.update("""
                update connector c set status = 'CHARGING', last_status_at = ?, updated_at = now(), version = version + 1
                  from charging_order o
                 where o.tenant_id = ? and o.id = ? and c.tenant_id = o.tenant_id and c.id = o.connector_id
                """, JdbcTimes.timestamp(occurredAt), route.tenantId(), orderId);
    }

    private void recordMeter(DeviceRoute route, Instant occurredAt, JsonNode payload) {
        int connectorNo = requiredInt(payload, "connectorNo");
        UUID orderId = optionalUuid(payload, "orderId").orElse(null);
        UUID connectorId = jdbc.query("""
                select id from connector where tenant_id = ? and device_id = ? and connector_no = ?
                """, (result, row) -> result.getObject(1, UUID.class), route.tenantId(), route.deviceId(), connectorNo)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown connector"));
        UUID sessionId = orderId == null ? null : jdbc.query("""
                select cs.id from charging_session cs
                  join charging_order o on o.tenant_id=cs.tenant_id and o.id=cs.order_id
                 where cs.tenant_id=? and cs.order_id=? and o.connector_id=?
                """, (result, row) -> result.getObject(1, UUID.class), route.tenantId(), orderId, connectorId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Meter session does not match connector"));
        jdbc.update("""
                insert into meter_sample
                    (tenant_id, device_id, connector_id, session_id, sampled_at, sequence_no,
                     energy_wh, power_w, voltage_mv, current_ma, raw_payload)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
                on conflict do nothing
                """, route.tenantId(), route.deviceId(), connectorId, sessionId, JdbcTimes.timestamp(occurredAt),
                requiredLong(payload, "sequenceNo"), requiredLong(payload, "energyWh"),
                optionalInt(payload, "powerW"), optionalInt(payload, "voltageMv"),
                optionalInt(payload, "currentMa"), payload.toString());
    }

    private void stopSession(DeviceRoute route, Instant occurredAt, JsonNode payload) {
        UUID orderId = requiredUuid(payload, "orderId");
        long meterStopWh = requiredLong(payload, "meterStopWh");
        if ("COMPLETED".equals(orderStatus(route.tenantId(), route.deviceId(), orderId))) return;
        SessionBilling billing = jdbc.query("""
                select cs.meter_start_wh, cs.started_at, o.connector_id, o.status as order_status, t.billing_mode,
                       coalesce((t.price_rules ->> 'durationUnitPriceMinor')::bigint,
                                (t.price_rules ->> 'unitPriceMinor')::bigint, 0) as duration_price_minor,
                       coalesce((t.price_rules ->> 'energyUnitPriceMinor')::bigint,
                                (t.price_rules ->> 'unitPriceMinor')::bigint, 0) as energy_price_minor,
                       coalesce((t.price_rules ->> 'minimumAmountMinor')::bigint, 0) as minimum_amount_minor
                  from charging_session cs
                  join charging_order o on o.id = cs.order_id and o.tenant_id = cs.tenant_id
                  join connector c on c.tenant_id=o.tenant_id and c.id=o.connector_id
                  join tariff t on t.id = o.tariff_id and t.tenant_id = o.tenant_id
                 where cs.tenant_id = ? and cs.order_id = ? and o.status in ('CHARGING', 'STOP_PENDING')
                   and c.device_id=?
                   for update of cs, o
                """, (result, row) -> new SessionBilling(
                        result.getLong("meter_start_wh"), result.getTimestamp("started_at").toInstant(),
                        result.getObject("connector_id", UUID.class), result.getString("order_status"),
                        result.getString("billing_mode"),
                        result.getLong("duration_price_minor"), result.getLong("energy_price_minor"),
                        result.getLong("minimum_amount_minor")),
                route.tenantId(), orderId, route.deviceId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Charging session cannot be stopped"));
        var result = tariffCalculator.calculate(new PriceRule(Mode.valueOf(billing.mode()),
                        billing.durationPriceMinor(), billing.energyPriceMinor(), billing.minimumAmountMinor()),
                billing.startedAt(), occurredAt, billing.meterStartWh(), meterStopWh);
        jdbc.update("""
                update charging_session set meter_stop_wh = ?, energy_wh = ?, stopped_at = ?, stop_reason = ?,
                       updated_at = now(), version = version + 1
                 where tenant_id = ? and order_id = ?
                """, meterStopWh, result.energyWh(), JdbcTimes.timestamp(occurredAt),
                textOrDefault(payload.path("reason"), "DEVICE_STOP"),
                route.tenantId(), orderId);
        jdbc.update("""
                update charging_order set status = 'COMPLETED', stopped_at = ?, payable_amount_minor = ?,
                       updated_at = now(), version = version + 1
                 where tenant_id = ? and id = ?
                """, JdbcTimes.timestamp(occurredAt), result.amountMinor(), route.tenantId(), orderId);
        recordOrderStatus(route.tenantId(), orderId, billing.orderStatus(), "COMPLETED",
                textOrDefault(payload.path("reason"), "DEVICE_STOP"), "device:" + route.deviceId());
        jdbc.update("""
                update connector set status = 'AVAILABLE', last_status_at = ?, updated_at = now(), version = version + 1
                 where tenant_id = ? and id = ?
                """, JdbcTimes.timestamp(occurredAt), route.tenantId(), billing.connectorId());
    }

    private void recordAlarm(DeviceRoute route, DeviceEnvelope envelope, JsonNode payload) {
        int connectorNo = optionalInt(payload, "connectorNo") == null ? 0 : optionalInt(payload, "connectorNo");
        UUID connectorId = connectorNo <= 0 ? null : jdbc.query("""
                select id from connector where tenant_id=? and device_id=? and connector_no=?
                """, (result, row) -> result.getObject(1, UUID.class), route.tenantId(), route.deviceId(), connectorNo)
                .stream().findFirst().orElse(null);
        String severity = textOrDefault(payload.path("severity"), "WARNING").toUpperCase();
        if (!Set.of("INFO", "WARNING", "CRITICAL").contains(severity)) severity = "WARNING";
        String externalId = textOrDefault(payload.path("alarmId"), envelope.messageId().toString());
        String code = textOrDefault(payload.path("alarmCode"), "DEVICE_ALARM");
        String message = textOrDefault(payload.path("message"), code);
        jdbc.update("""
                insert into device_alarm
                    (id, tenant_id, device_id, connector_id, external_alarm_id, alarm_code,
                     severity, message, status, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'OPEN', ?)
                on conflict (tenant_id, device_id, external_alarm_id) do nothing
                """, UUID.randomUUID(), route.tenantId(), route.deviceId(), connectorId, externalId,
                code, severity, message, JdbcTimes.timestamp(envelope.occurredAt()));
        jdbc.update("update device set status = 'FAULTED', updated_at = now() where tenant_id = ? and id = ?",
                route.tenantId(), route.deviceId());
    }

    private void recordOrderStatus(UUID tenantId, UUID orderId, String from, String to,
                                   String reason, String actor) {
        jdbc.update("""
                insert into order_status_history
                    (id, tenant_id, order_id, from_status, to_status, reason, actor_subject)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, orderId, from, to, reason, actor);
    }

    private String orderStatus(UUID tenantId, UUID deviceId, UUID orderId) {
        return jdbc.query("""
                select o.status from charging_order o
                  join connector c on c.tenant_id=o.tenant_id and c.id=o.connector_id
                 where o.tenant_id=? and o.id=? and c.device_id=? for update of o
                """, (result, row) -> result.getString(1), tenantId, orderId, deviceId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown charging order"));
    }

    private static String requiredText(JsonNode payload, String field) {
        String value = textOrDefault(payload.path(field), "");
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private static int requiredInt(JsonNode payload, String field) {
        if (!payload.has(field) || !payload.path(field).isIntegralNumber()) throw new IllegalArgumentException(field + " is required");
        return payload.path(field).asInt();
    }
    private static long requiredLong(JsonNode payload, String field) {
        if (!payload.has(field) || !payload.path(field).isIntegralNumber()) throw new IllegalArgumentException(field + " is required");
        long value = payload.path(field).asLong();
        if (value < 0) throw new IllegalArgumentException(field + " cannot be negative");
        return value;
    }
    private static Integer optionalInt(JsonNode payload, String field) {
        return payload.has(field) && payload.path(field).isIntegralNumber() ? payload.path(field).asInt() : null;
    }
    private static Optional<UUID> optionalUuid(JsonNode payload, String field) {
        String value = textOrDefault(payload.path(field), "");
        return value.isBlank() ? Optional.empty() : Optional.of(UUID.fromString(value));
    }
    private static UUID requiredUuid(JsonNode payload, String field) {
        return optionalUuid(payload, field).orElseThrow(() -> new IllegalArgumentException(field + " is required"));
    }

    private static String textOrDefault(JsonNode node, String defaultValue) {
        if (node.isMissingNode() || node.isNull()) return defaultValue;
        String value = node.asString(defaultValue);
        return value.isBlank() ? defaultValue : value;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable result = failure;
        for (int depth = 0; depth < 32 && result.getCause() != null && result.getCause() != result; depth++) {
            result = result.getCause();
        }
        return result;
    }

    private static String safeDetail(Throwable failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return failure.getClass().getSimpleName();
        String singleLine = message.replace('\r', ' ').replace('\n', ' ');
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
    }

    record DeviceRoute(UUID tenantId, UUID deviceId) { }
    record SessionBilling(long meterStartWh, Instant startedAt, UUID connectorId, String orderStatus, String mode,
                          long durationPriceMinor, long energyPriceMinor, long minimumAmountMinor) { }
}
