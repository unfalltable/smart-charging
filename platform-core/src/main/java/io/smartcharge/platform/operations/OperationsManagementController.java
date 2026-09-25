package io.smartcharge.platform.operations;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
final class OperationsManagementController {
    private static final Set<String> ORDER_STATES = Set.of(
            "CREATED", "START_PENDING", "CHARGING", "STOP_PENDING", "COMPLETED", "CANCELLED", "FAILED");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Map<String, Set<String>> WORK_ORDER_TRANSITIONS = Map.of(
            "OPEN", Set.of("ASSIGNED", "IN_PROGRESS", "CANCELLED"),
            "ASSIGNED", Set.of("IN_PROGRESS", "CANCELLED"),
            "IN_PROGRESS", Set.of("RESOLVED", "CANCELLED"),
            "RESOLVED", Set.of("CLOSED", "IN_PROGRESS"));
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;

    OperationsManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
    }

    @GetMapping("/orders")
    List<AdminOrderView> orders(@RequestParam(required = false) String status,
                                @RequestParam(required = false) String query,
                                @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
                                @RequestParam(defaultValue = "0") @Min(0) int offset) {
        if (status != null && !ORDER_STATES.contains(status)) throw new IllegalArgumentException("Invalid order status");
        UUID tenantId = TenantContext.requireTenantId();
        String search = query == null || query.isBlank() ? null : "%" + query.trim() + "%";
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select o.id, o.order_no, o.status, o.currency, o.payable_amount_minor, o.paid_amount_minor,
                       o.created_at, o.started_at, o.stopped_at, c.connector_no, d.device_code,
                       s.name as station_name, coalesce(cs.energy_wh, 0) as energy_wh,
                       coalesce(cu.display_name, '') as customer_name
                  from charging_order o
                  join connector c on c.tenant_id=o.tenant_id and c.id=o.connector_id
                  join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                  join station s on s.tenant_id=d.tenant_id and s.id=d.station_id
                  join customer cu on cu.tenant_id=o.tenant_id and cu.id=o.customer_id
                  left join charging_session cs on cs.tenant_id=o.tenant_id and cs.order_id=o.id
                 where o.tenant_id = ? and (cast(? as varchar) is null or o.status = ?)
                   and (cast(? as varchar) is null or o.order_no ilike ? or cu.display_name ilike ? or d.device_code ilike ?)
                 order by o.created_at desc limit ? offset ?
                """, (result, row) -> new AdminOrderView(
                    result.getObject("id", UUID.class), result.getString("order_no"), result.getString("status"),
                    result.getString("station_name"), result.getString("device_code"),
                    result.getInt("connector_no"), result.getString("customer_name"),
                    result.getLong("energy_wh"), result.getLong("payable_amount_minor"),
                    result.getLong("paid_amount_minor"), result.getString("currency"),
                    timestamp(result.getTimestamp("created_at")), timestamp(result.getTimestamp("started_at")),
                    timestamp(result.getTimestamp("stopped_at"))),
                tenantId, status, status, search, search, search, search, limit, offset));
    }

    @PostMapping("/orders/{orderId}/cancel")
    Map<String, Object> cancelPendingOrder(@PathVariable UUID orderId,
                                            @Valid @RequestBody CancelOrderRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            PendingOrder order = jdbc.query("""
                    select status, connector_id from charging_order
                     where tenant_id = ? and id = ? for update
                    """, (result, row) -> new PendingOrder(result.getString("status"),
                    result.getObject("connector_id", UUID.class)), tenantId, orderId).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Order does not exist"));
            if (!Set.of("CREATED", "START_PENDING").contains(order.status())) {
                throw new DomainException("Only an order that has not started can be cancelled");
            }
            jdbc.update("""
                    update charging_order set status='CANCELLED', updated_at=now(), version=version+1
                     where tenant_id=? and id=?
                    """, tenantId, orderId);
            jdbc.update("""
                    update connector set status='AVAILABLE', updated_at=now(), version=version+1
                     where tenant_id=? and id=? and status='RESERVED'
                    """, tenantId, order.connectorId());
            jdbc.update("""
                    update device_command set status='EXPIRED', failure_code='ORDER_CANCELLED', updated_at=now(),
                                              version=version+1
                     where tenant_id=? and order_id=? and status in ('PENDING','PUBLISHED')
                    """, tenantId, orderId);
            jdbc.update("""
                    insert into order_status_history
                        (id, tenant_id, order_id, from_status, to_status, reason, actor_subject)
                    values (?, ?, ?, ?, 'CANCELLED', ?, 'operator')
                    """, UUID.randomUUID(), tenantId, orderId, order.status(), request.reason());
            audit.record("ORDER_CANCELLED", "charging_order", orderId,
                    Map.of("status", order.status()), Map.of("status", "CANCELLED", "reason", request.reason()));
            return Map.of("id", orderId, "status", "CANCELLED");
        });
    }

    @GetMapping("/alarms")
    List<AlarmView> alarms(@RequestParam(required = false) String status,
                           @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select a.id, a.device_id, d.device_code, a.connector_id, a.alarm_code, a.severity,
                       a.message, a.status, a.occurred_at, a.acknowledged_at, a.resolved_at
                  from device_alarm a join device d on d.tenant_id=a.tenant_id and d.id=a.device_id
                 where a.tenant_id=? and (cast(? as varchar) is null or a.status=?)
                 order by case a.severity when 'CRITICAL' then 1 when 'WARNING' then 2 else 3 end,
                          a.occurred_at desc limit ?
                """, (result, row) -> new AlarmView(
                    result.getObject("id", UUID.class), result.getObject("device_id", UUID.class),
                    result.getString("device_code"), result.getObject("connector_id", UUID.class),
                    result.getString("alarm_code"), result.getString("severity"), result.getString("message"),
                    result.getString("status"), result.getTimestamp("occurred_at").toInstant(),
                    timestamp(result.getTimestamp("acknowledged_at")), timestamp(result.getTimestamp("resolved_at"))),
                tenantId, blankToNull(status), blankToNull(status), limit));
    }

    @PostMapping("/alarms/{alarmId}/acknowledge")
    Map<String, Object> acknowledgeAlarm(@PathVariable UUID alarmId) {
        return changeAlarmState(alarmId, "OPEN", "ACKNOWLEDGED", "ALARM_ACKNOWLEDGED");
    }

    @PostMapping("/alarms/{alarmId}/resolve")
    Map<String, Object> resolveAlarm(@PathVariable UUID alarmId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update device_alarm set status='RESOLVED', resolved_at=now(), updated_at=now()
                     where tenant_id=? and id=? and status in ('OPEN','ACKNOWLEDGED')
                    """, tenantId, alarmId);
            if (changed != 1) throw new DomainException("Alarm cannot be resolved from its current state");
            audit.record("ALARM_RESOLVED", "device_alarm", alarmId, null, Map.of("status", "RESOLVED"));
            return Map.of("id", alarmId, "status", "RESOLVED");
        });
    }

    @GetMapping("/work-orders")
    List<WorkOrderView> workOrders(@RequestParam(required = false) String status) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, work_order_no, alarm_id, device_id, connector_id, title, description,
                       priority, status, assignee_subject, due_at, resolved_at, created_at, version
                  from work_order where tenant_id=? and (cast(? as varchar) is null or status=?)
                 order by case priority when 'URGENT' then 1 when 'HIGH' then 2 when 'NORMAL' then 3 else 4 end,
                          created_at desc limit 500
                """, (result, row) -> new WorkOrderView(
                    result.getObject("id", UUID.class), result.getString("work_order_no"),
                    result.getObject("alarm_id", UUID.class), result.getObject("device_id", UUID.class),
                    result.getObject("connector_id", UUID.class), result.getString("title"),
                    result.getString("description"), result.getString("priority"), result.getString("status"),
                    result.getString("assignee_subject"), timestamp(result.getTimestamp("due_at")),
                    timestamp(result.getTimestamp("resolved_at")), result.getTimestamp("created_at").toInstant(),
                    result.getLong("version")), tenantId, blankToNull(status), blankToNull(status)));
    }

    @PostMapping("/work-orders")
    @ResponseStatus(HttpStatus.CREATED)
    WorkOrderView createWorkOrder(@Valid @RequestBody CreateWorkOrderRequest request) {
        if (!PRIORITIES.contains(request.priority())) throw new IllegalArgumentException("Invalid work order priority");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID id = UUID.randomUUID();
            String number = "WO-" + Instant.now().toEpochMilli() + "-" + id.toString().substring(0, 6);
            jdbc.update("""
                    insert into work_order
                        (id, tenant_id, work_order_no, alarm_id, device_id, connector_id, title,
                         description, priority, status, assignee_subject, due_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, number, request.alarmId(), request.deviceId(), request.connectorId(),
                    request.title(), request.description(), request.priority(),
                    request.assigneeSubject() == null ? "OPEN" : "ASSIGNED", request.assigneeSubject(),
                    JdbcTimes.nullableTimestamp(request.dueAt()));
            WorkOrderView created = findWorkOrder(tenantId, id);
            audit.record("WORK_ORDER_CREATED", "work_order", id, null, created);
            return created;
        });
    }

    @PostMapping("/work-orders/{workOrderId}/transition")
    WorkOrderView transitionWorkOrder(@PathVariable UUID workOrderId,
                                       @Valid @RequestBody TransitionWorkOrderRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            WorkOrderView before = findWorkOrderForUpdate(tenantId, workOrderId);
            Set<String> allowed = WORK_ORDER_TRANSITIONS.getOrDefault(before.status(), Set.of());
            if (!allowed.contains(request.status())) throw new DomainException("Invalid work order transition");
            String assignee = request.assigneeSubject() == null ? before.assigneeSubject() : request.assigneeSubject();
            if (Set.of("ASSIGNED", "IN_PROGRESS").contains(request.status())
                    && (assignee == null || assignee.isBlank())) {
                throw new IllegalArgumentException("Assignee is required for this state");
            }
            int changed = jdbc.update("""
                    update work_order set status=?, assignee_subject=?,
                        resolved_at=case when ?='RESOLVED' then now() else resolved_at end,
                        updated_at=now(), version=version+1
                     where tenant_id=? and id=? and version=?
                    """, request.status(), assignee, request.status(), tenantId, workOrderId, request.version());
            if (changed != 1) throw new DomainException("Work order was modified by another operator");
            WorkOrderView after = findWorkOrder(tenantId, workOrderId);
            audit.record("WORK_ORDER_TRANSITIONED", "work_order", workOrderId, before, after);
            return after;
        });
    }

    @GetMapping("/audit")
    List<AuditView> audit(@RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, actor_subject, action, resource_type, resource_id, occurred_at
                  from audit_log where tenant_id=? order by occurred_at desc limit ?
                """, (result, row) -> new AuditView(
                    result.getObject("id", UUID.class), result.getString("actor_subject"),
                    result.getString("action"), result.getString("resource_type"),
                    result.getString("resource_id"), result.getTimestamp("occurred_at").toInstant()), tenantId, limit));
    }

    private Map<String, Object> changeAlarmState(UUID alarmId, String from, String to, String action) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update device_alarm set status=?, acknowledged_at=now(), updated_at=now()
                     where tenant_id=? and id=? and status=?
                    """, to, tenantId, alarmId, from);
            if (changed != 1) throw new DomainException("Alarm cannot be acknowledged from its current state");
            audit.record(action, "device_alarm", alarmId, Map.of("status", from), Map.of("status", to));
            return Map.of("id", alarmId, "status", to);
        });
    }

    private WorkOrderView findWorkOrder(UUID tenantId, UUID id) {
        return workOrderQuery(tenantId, id, false);
    }

    private WorkOrderView findWorkOrderForUpdate(UUID tenantId, UUID id) {
        return workOrderQuery(tenantId, id, true);
    }

    private WorkOrderView workOrderQuery(UUID tenantId, UUID id, boolean lock) {
        String sql = """
                select id, work_order_no, alarm_id, device_id, connector_id, title, description,
                       priority, status, assignee_subject, due_at, resolved_at, created_at, version
                  from work_order where tenant_id=? and id=?
                """ + (lock ? " for update" : "");
        return jdbc.query(sql, (result, row) -> new WorkOrderView(
                result.getObject("id", UUID.class), result.getString("work_order_no"),
                result.getObject("alarm_id", UUID.class), result.getObject("device_id", UUID.class),
                result.getObject("connector_id", UUID.class), result.getString("title"),
                result.getString("description"), result.getString("priority"), result.getString("status"),
                result.getString("assignee_subject"), timestamp(result.getTimestamp("due_at")),
                timestamp(result.getTimestamp("resolved_at")), result.getTimestamp("created_at").toInstant(),
                result.getLong("version")), tenantId, id).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Work order does not exist"));
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static Instant timestamp(java.sql.Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }

    record CancelOrderRequest(@NotBlank @Size(max = 160) String reason) { }
    record CreateWorkOrderRequest(UUID alarmId, UUID deviceId, UUID connectorId,
                                  @NotBlank @Size(max = 200) String title,
                                  @Size(max = 1000) String description,
                                  @NotBlank String priority, @Size(max = 160) String assigneeSubject,
                                  Instant dueAt) { }
    record TransitionWorkOrderRequest(@NotBlank String status, @Size(max = 160) String assigneeSubject,
                                      @Min(0) long version) { }
    record PendingOrder(String status, UUID connectorId) { }
    record AdminOrderView(UUID id, String orderNo, String status, String stationName, String deviceCode,
                          int connectorNo, String customerName, long energyWh, long payableAmountMinor,
                          long paidAmountMinor, String currency, Instant createdAt, Instant startedAt, Instant stoppedAt) { }
    record AlarmView(UUID id, UUID deviceId, String deviceCode, UUID connectorId, String alarmCode,
                     String severity, String message, String status, Instant occurredAt,
                     Instant acknowledgedAt, Instant resolvedAt) { }
    record WorkOrderView(UUID id, String workOrderNo, UUID alarmId, UUID deviceId, UUID connectorId,
                         String title, String description, String priority, String status,
                         String assigneeSubject, Instant dueAt, Instant resolvedAt, Instant createdAt, long version) { }
    record AuditView(UUID id, String actorSubject, String action, String resourceType,
                     String resourceId, Instant occurredAt) { }
}
