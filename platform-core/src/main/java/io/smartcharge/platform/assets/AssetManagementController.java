package io.smartcharge.platform.assets;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/assets")
final class AssetManagementController {
    private static final Set<String> STATION_STATES = Set.of("DRAFT", "ACTIVE", "OFFLINE", "CLOSED");
    private static final Set<String> DEVICE_STATES = Set.of("PROVISIONING", "ONLINE", "OFFLINE", "FAULTED", "RETIRED");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;

    AssetManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
    }

    @GetMapping("/stations")
    List<StationView> stations(@RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select s.id, s.organization_id, o.name as organization_name,
                       o.organization_type, s.code, s.name, s.address, s.status, s.version,
                       count(distinct d.id) as device_count,
                       count(c.id) as connector_count
                  from station s
                  join operator_organization o on o.tenant_id=s.tenant_id and o.id=s.organization_id
                  left join device d on d.tenant_id = s.tenant_id and d.station_id = s.id and d.status <> 'RETIRED'
                  left join connector c on c.tenant_id = d.tenant_id and c.device_id = d.id
                 where s.tenant_id = ?
                 group by s.id, o.id
                 order by s.created_at desc
                 limit ?
                """, (result, row) -> new StationView(
                    result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                    result.getString("organization_name"), result.getString("organization_type"),
                    result.getString("code"), result.getString("name"),
                    result.getString("address"), result.getString("status"), result.getLong("version"),
                    result.getLong("device_count"), result.getLong("connector_count")), tenantId, limit));
    }

    @PostMapping("/stations")
    @ResponseStatus(HttpStatus.CREATED)
    StationView createStation(@Valid @RequestBody CreateStationRequest request) {
        requireState(STATION_STATES, request.status(), "station");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            requireActiveOrganization(tenantId, request.organizationId());
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into station
                        (id, tenant_id, organization_id, code, name, address, longitude, latitude, timezone, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, tenantId, request.organizationId(), request.code(), request.name(), request.address(),
                    request.longitude(), request.latitude(), request.timezone(), request.status());
            audit.record("STATION_CREATED", "station", id, null, request);
            return station(tenantId, id);
        });
    }

    @PatchMapping("/stations/{stationId}")
    StationView updateStation(@PathVariable UUID stationId, @Valid @RequestBody UpdateStationRequest request) {
        requireState(STATION_STATES, request.status(), "station");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            StationView before = station(tenantId, stationId);
            UUID organizationId = request.organizationId() == null
                    ? before.organizationId() : request.organizationId();
            requireActiveOrganization(tenantId, organizationId);
            int changed = jdbc.update("""
                    update station set organization_id=?, name = ?, address = ?, status = ?,
                                       updated_at = now(), version = version + 1
                     where tenant_id = ? and id = ? and version = ?
                    """, organizationId, request.name(), request.address(), request.status(), tenantId, stationId,
                    request.version());
            if (changed != 1) throw new DomainException("Station was modified by another operator");
            StationView after = station(tenantId, stationId);
            audit.record("STATION_UPDATED", "station", stationId, before, after);
            return after;
        });
    }

    @GetMapping("/devices")
    List<DeviceView> devices(@RequestParam(required = false) UUID stationId,
                             @RequestParam(defaultValue = "200") @Min(1) @Max(500) int limit) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select d.id, d.station_id, s.name as station_name, d.device_code, d.protocol_code,
                       d.product_model, d.firmware_version, d.connector_count, d.status, d.last_seen_at, d.version
                  from device d join station s on s.tenant_id = d.tenant_id and s.id = d.station_id
                 where d.tenant_id = ? and (cast(? as uuid) is null or d.station_id = ?)
                 order by d.created_at desc limit ?
                """, (result, row) -> new DeviceView(
                    result.getObject("id", UUID.class), result.getObject("station_id", UUID.class),
                    result.getString("station_name"), result.getString("device_code"),
                    result.getString("protocol_code"), result.getString("product_model"),
                    result.getString("firmware_version"), result.getInt("connector_count"),
                    result.getString("status"), timestamp(result.getTimestamp("last_seen_at")),
                    result.getLong("version")), tenantId, stationId, stationId, limit));
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    DeviceView createDevice(@Valid @RequestBody CreateDeviceRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            boolean stationExists = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from station where tenant_id = ? and id = ? and status <> 'CLOSED')
                    """, Boolean.class, tenantId, request.stationId()));
            if (!stationExists) throw new IllegalArgumentException("Station does not exist or is closed");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into device
                        (id, tenant_id, station_id, device_code, protocol_code, product_model,
                         firmware_version, connector_count, status)
                    values (?, ?, ?, ?, ?, ?, ?, ?, 'PROVISIONING')
                    """, id, tenantId, request.stationId(), request.deviceCode(), request.protocolCode(),
                    request.productModel(), request.firmwareVersion(), request.connectorCount());
            for (int connectorNo = 1; connectorNo <= request.connectorCount(); connectorNo++) {
                jdbc.update("""
                        insert into connector
                            (id, tenant_id, device_id, connector_no, external_code, rated_power_w, status)
                        values (?, ?, ?, ?, ?, ?, 'OFFLINE')
                        """, UUID.randomUUID(), tenantId, id, connectorNo,
                        request.deviceCode() + "-" + String.format("%02d", connectorNo), request.ratedPowerW());
            }
            audit.record("DEVICE_CREATED", "device", id, null, request);
            String stationName = jdbc.queryForObject(
                    "select name from station where tenant_id = ? and id = ?", String.class, tenantId, request.stationId());
            return new DeviceView(id, request.stationId(), stationName, request.deviceCode(), request.protocolCode(),
                    request.productModel(), request.firmwareVersion(), request.connectorCount(),
                    "PROVISIONING", null, 0);
        });
    }

    @PatchMapping("/devices/{deviceId}/status")
    Map<String, Object> updateDeviceStatus(@PathVariable UUID deviceId,
                                            @Valid @RequestBody ChangeStatusRequest request) {
        requireState(DEVICE_STATES, request.status(), "device");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            String before = jdbc.query("select status from device where tenant_id = ? and id = ? for update",
                    (result, row) -> result.getString(1), tenantId, deviceId).stream().findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Device does not exist"));
            jdbc.update("""
                    update device set status = ?, updated_at = now(), version = version + 1
                     where tenant_id = ? and id = ?
                    """, request.status(), tenantId, deviceId);
            if (Set.of("OFFLINE", "FAULTED", "RETIRED").contains(request.status())) {
                jdbc.update("""
                        update connector set status = ?, updated_at = now(), version = version + 1
                         where tenant_id = ? and device_id = ? and status <> 'DISABLED'
                        """, "RETIRED".equals(request.status()) ? "DISABLED" : "OFFLINE", tenantId, deviceId);
            }
            audit.record("DEVICE_STATUS_CHANGED", "device", deviceId,
                    Map.of("status", before), Map.of("status", request.status()));
            return Map.of("id", deviceId, "status", request.status());
        });
    }

    @GetMapping("/connectors")
    List<ConnectorView> connectors(@RequestParam(required = false) UUID deviceId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select c.id, c.device_id, d.device_code, c.connector_no, c.external_code, c.rated_power_w,
                       c.status, c.tariff_id, c.last_status_at, c.version
                  from connector c join device d on d.tenant_id = c.tenant_id and d.id = c.device_id
                 where c.tenant_id = ? and (cast(? as uuid) is null or c.device_id = ?)
                 order by d.device_code, c.connector_no
                """, (result, row) -> new ConnectorView(
                    result.getObject("id", UUID.class), result.getObject("device_id", UUID.class),
                    result.getString("device_code"), result.getInt("connector_no"),
                    result.getString("external_code"), result.getObject("rated_power_w", Integer.class),
                    result.getString("status"), result.getObject("tariff_id", UUID.class),
                    timestamp(result.getTimestamp("last_status_at")), result.getLong("version")),
                tenantId, deviceId, deviceId));
    }

    private StationView station(UUID tenantId, UUID id) {
        return jdbc.query("""
                select s.id, s.organization_id, o.name as organization_name, o.organization_type,
                       s.code, s.name, s.address, s.status, s.version,
                       (select count(*) from device d where d.tenant_id=s.tenant_id and d.station_id=s.id) device_count,
                       (select count(*) from connector c join device d on d.id=c.device_id and d.tenant_id=c.tenant_id
                         where d.tenant_id=s.tenant_id and d.station_id=s.id) connector_count
                  from station s
                  join operator_organization o on o.tenant_id=s.tenant_id and o.id=s.organization_id
                 where s.tenant_id = ? and s.id = ?
                """, (result, row) -> new StationView(
                    result.getObject("id", UUID.class), result.getObject("organization_id", UUID.class),
                    result.getString("organization_name"), result.getString("organization_type"),
                    result.getString("code"), result.getString("name"),
                    result.getString("address"), result.getString("status"), result.getLong("version"),
                    result.getLong("device_count"), result.getLong("connector_count")), tenantId, id)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Station does not exist"));
    }

    private void requireActiveOrganization(UUID tenantId, UUID organizationId) {
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from operator_organization
                               where tenant_id=? and id=? and status='ACTIVE')
                """, Boolean.class, tenantId, organizationId));
        if (!active) throw new DomainException("Station organization does not exist or is not active");
    }

    private static void requireState(Set<String> allowed, String state, String resource) {
        if (!allowed.contains(state)) throw new IllegalArgumentException("Invalid " + resource + " status");
    }

    private static Instant timestamp(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    record CreateStationRequest(
            @NotNull UUID organizationId,
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,64}") String code,
            @NotBlank @Size(max = 160) String name,
            @Size(max = 500) String address,
            Double longitude,
            Double latitude,
            @NotBlank @Size(max = 64) String timezone,
            @NotBlank String status) { }

    record UpdateStationRequest(UUID organizationId, @NotBlank @Size(max = 160) String name,
                                @Size(max = 500) String address,
                                @NotBlank String status, @Min(0) long version) { }

    record CreateDeviceRequest(
            @NotNull UUID stationId,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._:-]{2,96}") String deviceCode,
            @NotBlank @Size(max = 64) String protocolCode,
            @NotBlank @Size(max = 96) String productModel,
            @Size(max = 64) String firmwareVersion,
            @Min(1) @Max(128) int connectorCount,
            @Min(1) int ratedPowerW) { }

    record ChangeStatusRequest(@NotBlank String status) { }
    record StationView(UUID id, UUID organizationId, String organizationName, String organizationType,
                       String code, String name, String address, String status, long version,
                       long deviceCount, long connectorCount) { }
    record DeviceView(UUID id, UUID stationId, String stationName, String deviceCode, String protocolCode,
                      String productModel, String firmwareVersion, int connectorCount, String status,
                      Instant lastSeenAt, long version) { }
    record ConnectorView(UUID id, UUID deviceId, String deviceCode, int connectorNo, String externalCode,
                         Integer ratedPowerW, String status, UUID tariffId, Instant lastStatusAt, long version) { }
}
