package io.smartcharge.platform.billing;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

@RestController
@RequestMapping("/api/v1/admin/tariffs")
final class TariffManagementController {
    private static final Set<String> MODES = Set.of("DURATION", "ENERGY", "HYBRID");
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    TariffManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
    }

    @GetMapping
    List<TariffView> list() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, name, currency, billing_mode, price_rules::text, effective_from, effective_until,
                       status, version
                  from tariff where tenant_id = ? order by created_at desc
                """, (result, row) -> new TariffView(
                    result.getObject("id", UUID.class), result.getString("name"), result.getString("currency"),
                    result.getString("billing_mode"), result.getString("price_rules"),
                    result.getTimestamp("effective_from").toInstant(),
                    result.getTimestamp("effective_until") == null ? null : result.getTimestamp("effective_until").toInstant(),
                    result.getString("status"), result.getLong("version")), tenantId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    TariffView create(@Valid @RequestBody CreateTariffRequest request) {
        validateRules(request);
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID id = UUID.randomUUID();
            String priceRules = priceRules(request);
            jdbc.update("""
                    insert into tariff
                        (id, tenant_id, name, currency, billing_mode, price_rules,
                         effective_from, effective_until, status)
                    values (?, ?, ?, 'CNY', ?, cast(? as jsonb), ?, ?, 'DRAFT')
                    """, id, tenantId, request.name(), request.billingMode(), priceRules,
                    JdbcTimes.timestamp(request.effectiveFrom()), JdbcTimes.nullableTimestamp(request.effectiveUntil()));
            audit.record("TARIFF_CREATED", "tariff", id, null, request);
            return new TariffView(id, request.name(), "CNY", request.billingMode(), priceRules,
                    request.effectiveFrom(), request.effectiveUntil(), "DRAFT", 0);
        });
    }

    @PostMapping("/{tariffId}/activate")
    TariffView activate(@PathVariable UUID tariffId, @Valid @RequestBody VersionRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update tariff set status = 'ACTIVE', updated_at = now(), version = version + 1
                     where tenant_id = ? and id = ? and status = 'DRAFT' and version = ?
                    """, tenantId, tariffId, request.version());
            if (changed != 1) throw new DomainException("Only an unchanged draft tariff can be activated");
            TariffView after = find(tenantId, tariffId);
            audit.record("TARIFF_ACTIVATED", "tariff", tariffId, null, after);
            return after;
        });
    }

    @PostMapping("/{tariffId}/expire")
    TariffView expire(@PathVariable UUID tariffId, @Valid @RequestBody VersionRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            int changed = jdbc.update("""
                    update tariff set status = 'EXPIRED', effective_until = coalesce(effective_until, now()),
                                      updated_at = now(), version = version + 1
                     where tenant_id = ? and id = ? and status = 'ACTIVE' and version = ?
                    """, tenantId, tariffId, request.version());
            if (changed != 1) throw new DomainException("Only an unchanged active tariff can be expired");
            TariffView after = find(tenantId, tariffId);
            audit.record("TARIFF_EXPIRED", "tariff", tariffId, null, after);
            return after;
        });
    }

    @PostMapping("/{tariffId}/connectors/{connectorId}")
    Map<String, Object> assign(@PathVariable UUID tariffId, @PathVariable UUID connectorId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            boolean active = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from tariff where tenant_id = ? and id = ? and status = 'ACTIVE'
                        and effective_from <= now() and (effective_until is null or effective_until > now()))
                    """, Boolean.class, tenantId, tariffId));
            if (!active) throw new DomainException("Tariff is not currently active");
            int changed = jdbc.update("""
                    update connector set tariff_id = ?, updated_at = now(), version = version + 1
                     where tenant_id = ? and id = ? and status not in ('CHARGING', 'RESERVED')
                    """, tariffId, tenantId, connectorId);
            if (changed != 1) throw new DomainException("Connector does not exist or is currently in use");
            audit.record("CONNECTOR_TARIFF_ASSIGNED", "connector", connectorId, null,
                    Map.of("tariffId", tariffId));
            return Map.of("connectorId", connectorId, "tariffId", tariffId);
        });
    }

    private TariffView find(UUID tenantId, UUID tariffId) {
        return jdbc.query("""
                select id, name, currency, billing_mode, price_rules::text, effective_from, effective_until,
                       status, version from tariff where tenant_id = ? and id = ?
                """, (result, row) -> new TariffView(
                    result.getObject("id", UUID.class), result.getString("name"), result.getString("currency"),
                    result.getString("billing_mode"), result.getString("price_rules"),
                    result.getTimestamp("effective_from").toInstant(),
                    result.getTimestamp("effective_until") == null ? null : result.getTimestamp("effective_until").toInstant(),
                    result.getString("status"), result.getLong("version")), tenantId, tariffId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Tariff does not exist"));
    }

    private void validateRules(CreateTariffRequest request) {
        if (!MODES.contains(request.billingMode())) throw new IllegalArgumentException("Invalid billing mode");
        if (request.effectiveUntil() != null && !request.effectiveUntil().isAfter(request.effectiveFrom())) {
            throw new IllegalArgumentException("effectiveUntil must be later than effectiveFrom");
        }
        if ("DURATION".equals(request.billingMode()) && request.durationUnitPriceMinor() <= 0) {
            throw new IllegalArgumentException("Duration price must be positive");
        }
        if ("ENERGY".equals(request.billingMode()) && request.energyUnitPriceMinor() <= 0) {
            throw new IllegalArgumentException("Energy price must be positive");
        }
        if ("HYBRID".equals(request.billingMode())
                && (request.durationUnitPriceMinor() <= 0 || request.energyUnitPriceMinor() <= 0)) {
            throw new IllegalArgumentException("Hybrid prices must both be positive");
        }
    }

    private String priceRules(CreateTariffRequest request) {
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("durationUnitPriceMinor", request.durationUnitPriceMinor());
        rules.put("energyUnitPriceMinor", request.energyUnitPriceMinor());
        rules.put("minimumAmountMinor", request.minimumAmountMinor());
        try {
            return json.writeValueAsString(rules);
        } catch (Exception serializationFailure) {
            throw new IllegalArgumentException("Tariff rules cannot be serialized", serializationFailure);
        }
    }

    record CreateTariffRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank String billingMode,
            @Min(0) long durationUnitPriceMinor,
            @Min(0) long energyUnitPriceMinor,
            @Min(0) long minimumAmountMinor,
            @NotNull Instant effectiveFrom,
            Instant effectiveUntil) { }
    record VersionRequest(@Min(0) long version) { }
    record TariffView(UUID id, String name, String currency, String billingMode, String priceRules,
                      Instant effectiveFrom, Instant effectiveUntil, String status, long version) { }
}
