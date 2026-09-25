package io.smartcharge.platform.charging;

import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ChargingOrderRepository {
    private final JdbcTemplate jdbc;

    ChargingOrderRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<ChargingOrderService.CreatedOrder> findByIdempotencyKey(UUID tenantId, String key,
                                                                     UUID requestedConnectorId) {
        ExistingOrder existing = jdbc.query("""
                select id, order_no, status, connector_id
                  from charging_order
                 where tenant_id = ? and idempotency_key = ?
                """, (result, row) -> new ExistingOrder(mapCreatedOrder(result, row),
                result.getObject("connector_id", UUID.class)), tenantId, key).stream().findFirst().orElse(null);
        if (existing == null) return Optional.empty();
        if (!existing.connectorId().equals(requestedConnectorId)) {
            throw new DomainException("Idempotency key was reused for a different connector");
        }
        return Optional.of(existing.order());
    }

    List<OrderSummary> findForCustomer(UUID tenantId, UUID customerId) {
        return jdbc.query("""
                select o.id, o.order_no, s.name as station_name, c.connector_no, o.status,
                       coalesce(cs.energy_wh, 0) as energy_wh, o.payable_amount_minor, o.paid_amount_minor
                  from charging_order o
                  join connector c on c.id = o.connector_id and c.tenant_id = o.tenant_id
                  join device d on d.id = c.device_id and d.tenant_id = o.tenant_id
                  join station s on s.id = d.station_id and s.tenant_id = o.tenant_id
                  left join charging_session cs on cs.order_id = o.id and cs.tenant_id = o.tenant_id
                 where o.tenant_id = ? and o.customer_id = ?
                 order by o.created_at desc
                 limit 100
                """, (result, row) -> new OrderSummary(
                        result.getObject("id", UUID.class), result.getString("order_no"),
                        result.getString("station_name"), result.getInt("connector_no"),
                        ChargeOrderStatus.valueOf(result.getString("status")), result.getLong("energy_wh"),
                        result.getLong("payable_amount_minor"), result.getLong("paid_amount_minor")), tenantId, customerId);
    }

    OrderLock lockCustomerOrder(UUID tenantId, UUID customerId, UUID orderId) {
        return jdbc.query("""
                select o.status, o.connector_id, c.device_id, c.connector_no
                  from charging_order o
                  join connector c on c.id = o.connector_id and c.tenant_id = o.tenant_id
                 where o.tenant_id = ? and o.customer_id = ? and o.id = ?
                   for update of o
                """, (result, row) -> new OrderLock(
                        ChargeOrderStatus.valueOf(result.getString("status")),
                        result.getObject("connector_id", UUID.class),
                        result.getObject("device_id", UUID.class), result.getInt("connector_no")),
                tenantId, customerId, orderId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Charging order does not exist"));
    }

    ConnectorLock lockAvailableConnector(UUID tenantId, UUID connectorId) {
        return jdbc.query("""
                select device_id, status, tariff_id
                  from connector
                 where tenant_id = ? and id = ?
                   for update
                """, (result, row) -> new ConnectorLock(
                        result.getObject("device_id", UUID.class), result.getString("status"),
                        result.getObject("tariff_id", UUID.class)),
                tenantId, connectorId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Connector does not exist"));
    }

    boolean customerExists(UUID tenantId, UUID customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists(select 1 from customer where tenant_id = ? and id = ? and status = 'ACTIVE')",
                Boolean.class, tenantId, customerId));
    }

    boolean requiredAgreementsAccepted(UUID tenantId, UUID customerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select not exists (
                    select 1 from agreement_document d
                     where d.tenant_id=? and d.status='ACTIVE' and d.effective_at<=now()
                       and not exists (
                           select 1 from customer_agreement a
                            where a.tenant_id=d.tenant_id and a.document_id=d.id and a.customer_id=?
                       )
                )
                """, Boolean.class, tenantId, customerId));
    }

    boolean tariffExists(UUID tenantId, UUID tariffId, Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(
                    select 1 from tariff
                     where tenant_id = ? and id = ? and status = 'ACTIVE'
                       and effective_from <= ? and (effective_until is null or effective_until > ?)
                )
                """, Boolean.class, tenantId, tariffId, JdbcTimes.timestamp(now), JdbcTimes.timestamp(now)));
    }

    int insertOrder(ChargingOrder order, UUID tariffId, String orderNo, String idempotencyKey) {
        return jdbc.update("""
                insert into charging_order
                    (id, tenant_id, order_no, customer_id, connector_id, tariff_id, status, idempotency_key)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (tenant_id, idempotency_key) do nothing
                """, order.id(), order.tenantId(), orderNo, order.customerId(), order.connectorId(), tariffId,
                order.status().name(), idempotencyKey);
    }

    void reserveConnector(UUID tenantId, UUID connectorId) {
        int changed = jdbc.update("""
                update connector set status = 'RESERVED', updated_at = now(), version = version + 1
                 where tenant_id = ? and id = ? and status = 'AVAILABLE'
                """, tenantId, connectorId);
        if (changed != 1) throw new IllegalStateException("Connector reservation lost a concurrent update");
    }

    void insertSession(UUID id, UUID tenantId, UUID orderId) {
        jdbc.update("insert into charging_session (id, tenant_id, order_id) values (?, ?, ?)",
                id, tenantId, orderId);
    }

    void enqueueStartCommand(UUID commandId, UUID eventId, UUID tenantId, UUID deviceId,
                             UUID connectorId, UUID orderId, Instant expiresAt) {
        String commandPayload = "{\"orderId\":\"" + orderId + "\",\"connectorId\":\"" + connectorId + "\"}";
        jdbc.update("""
                insert into device_command
                    (id, tenant_id, device_id, connector_id, order_id, command_type, status, payload, expires_at)
                values (?, ?, ?, ?, ?, 'START_CHARGING', 'PENDING', cast(? as jsonb), ?)
                """, commandId, tenantId, deviceId, connectorId, orderId, commandPayload,
                JdbcTimes.timestamp(expiresAt));
        String eventPayload = "{\"commandId\":\"" + commandId + "\",\"deviceId\":\"" + deviceId + "\"}";
        jdbc.update("""
                insert into outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                values (?, ?, 'DeviceCommand', ?, 'DeviceCommandRequested', cast(? as jsonb), now())
                """, eventId, tenantId, commandId, eventPayload);
    }

    void requestStop(UUID commandId, UUID eventId, UUID tenantId, UUID deviceId,
                     UUID connectorId, UUID orderId, Instant expiresAt) {
        int changed = jdbc.update("""
                update charging_order set status = 'STOP_PENDING', updated_at = now(), version = version + 1
                 where tenant_id = ? and id = ? and status = 'CHARGING'
                """, tenantId, orderId);
        if (changed != 1) throw new IllegalStateException("Order stop lost a concurrent update");
        recordStatus(tenantId, orderId, "CHARGING", "STOP_PENDING", "CUSTOMER_REQUESTED_STOP", "customer");
        String commandPayload = "{\"orderId\":\"" + orderId + "\",\"connectorId\":\"" + connectorId + "\"}";
        jdbc.update("""
                insert into device_command
                    (id, tenant_id, device_id, connector_id, order_id, command_type, status, payload, expires_at)
                values (?, ?, ?, ?, ?, 'STOP_CHARGING', 'PENDING', cast(? as jsonb), ?)
                """, commandId, tenantId, deviceId, connectorId, orderId, commandPayload,
                JdbcTimes.timestamp(expiresAt));
        String eventPayload = "{\"commandId\":\"" + commandId + "\",\"deviceId\":\"" + deviceId + "\"}";
        jdbc.update("""
                insert into outbox_event
                    (id, tenant_id, aggregate_type, aggregate_id, event_type, payload, occurred_at)
                values (?, ?, 'DeviceCommand', ?, 'DeviceCommandRequested', cast(? as jsonb), now())
                """, eventId, tenantId, commandId, eventPayload);
    }

    void recordStatus(UUID tenantId, UUID orderId, String fromStatus, String toStatus,
                      String reason, String actorSubject) {
        jdbc.update("""
                insert into order_status_history
                    (id, tenant_id, order_id, from_status, to_status, reason, actor_subject)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, orderId, fromStatus, toStatus, reason, actorSubject);
    }

    private ChargingOrderService.CreatedOrder mapCreatedOrder(ResultSet result, int row) throws SQLException {
        return new ChargingOrderService.CreatedOrder(
                result.getObject("id", UUID.class), result.getString("order_no"),
                ChargeOrderStatus.valueOf(result.getString("status")));
    }

    record ConnectorLock(UUID deviceId, String status, UUID tariffId) { }
    record OrderLock(ChargeOrderStatus status, UUID connectorId, UUID deviceId, int connectorNo) { }
    record ExistingOrder(ChargingOrderService.CreatedOrder order, UUID connectorId) { }
}
