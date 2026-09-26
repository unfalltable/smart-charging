package io.smartcharge.platform.organization;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/organizations")
final class OrganizationManagementController {
    private static final Set<String> ROOT_TYPES = Set.of(
            "REGIONAL_OPERATOR", "FIRST_TIER_CONTRACTOR", "DIRECT_BRANCH");
    private static final Set<String> CHILD_TYPES = Set.of("SECOND_TIER_PARTNER", "SITE_PARTNER");
    private static final Set<String> STATES = Set.of("ACTIVE", "SUSPENDED", "CLOSED");

    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final AuditService audit;

    OrganizationManagementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.audit = audit;
    }

    @GetMapping("/tree")
    OrganizationTreeView tree() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            List<OrganizationView> organizations = organizations(tenantId);
            OrganizationView root = organizations.stream().filter(row -> row.hierarchyLevel() == 1)
                    .findFirst().orElseThrow(() -> new DomainException("Tenant root organization is missing"));
            List<OrganizationView> children = organizations.stream()
                    .filter(row -> row.hierarchyLevel() == 2).toList();
            OrganizationTotals totals = jdbc.queryForObject("""
                    select
                        (select count(*) from station where tenant_id=? and status<>'CLOSED') station_count,
                        (select count(*) from device where tenant_id=? and status<>'RETIRED') device_count,
                        (select count(*) from connector where tenant_id=? and status<>'DISABLED') connector_count,
                        (select count(*) from customer where tenant_id=? and status='ACTIVE') customer_count
                    """, (result, row) -> new OrganizationTotals(
                    result.getLong("station_count"), result.getLong("device_count"),
                    result.getLong("connector_count"), result.getLong("customer_count")),
                    tenantId, tenantId, tenantId, tenantId);
            return new OrganizationTreeView(root, children, totals);
        });
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    OrganizationView create(@Valid @RequestBody CreateOrganizationRequest request) {
        requireType(CHILD_TYPES, request.organizationType());
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            boolean validParent = Boolean.TRUE.equals(jdbc.queryForObject("""
                    select exists(select 1 from operator_organization
                                   where tenant_id=? and id=? and hierarchy_level=1 and status='ACTIVE')
                    """, Boolean.class, tenantId, request.parentId()));
            if (!validParent) throw new DomainException("Parent must be the active first-tier organization");
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    insert into operator_organization
                        (id, tenant_id, parent_id, code, name, organization_type, hierarchy_level,
                         contact_name, contact_mobile, status)
                    values (?, ?, ?, ?, ?, ?, 2, ?, ?, 'ACTIVE')
                    """, id, tenantId, request.parentId(), request.code(), request.name(),
                    request.organizationType(), blankToNull(request.contactName()), blankToNull(request.contactMobile()));
            OrganizationView view = organization(tenantId, id);
            audit.record("ORGANIZATION_CREATED", "operator_organization", id, null, view);
            return view;
        });
    }

    @PatchMapping("/{organizationId}")
    OrganizationView update(@PathVariable UUID organizationId,
                            @Valid @RequestBody UpdateOrganizationRequest request) {
        if (!STATES.contains(request.status())) throw new IllegalArgumentException("Invalid organization status");
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            OrganizationView before = organization(tenantId, organizationId);
            requireType(before.hierarchyLevel() == 1 ? ROOT_TYPES : CHILD_TYPES, request.organizationType());
            if (before.hierarchyLevel() == 1 && !"ACTIVE".equals(request.status())) {
                throw new DomainException("Use the tenant lifecycle to suspend or close a first-tier organization");
            }
            if (before.hierarchyLevel() == 2 && "CLOSED".equals(request.status())) {
                boolean hasLiveStations = Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from station
                                       where tenant_id=? and organization_id=? and status<>'CLOSED')
                        """, Boolean.class, tenantId, organizationId));
                if (hasLiveStations) throw new DomainException("Close or reassign active stations before closing the organization");
            }
            int changed = jdbc.update("""
                    update operator_organization
                       set name=?, organization_type=?, contact_name=?, contact_mobile=?, status=?,
                           updated_at=now(), version=version+1
                     where tenant_id=? and id=? and version=?
                    """, request.name(), request.organizationType(), blankToNull(request.contactName()),
                    blankToNull(request.contactMobile()), request.status(), tenantId, organizationId,
                    request.version());
            if (changed != 1) throw new DomainException("Organization was modified by another operator");
            if (before.hierarchyLevel() == 1) {
                jdbc.update("""
                        update tenant set display_name=?, updated_at=now(), version=version+1 where id=?
                        """, request.name(), tenantId);
            }
            OrganizationView after = organization(tenantId, organizationId);
            audit.record("ORGANIZATION_UPDATED", "operator_organization", organizationId, before, after);
            return after;
        });
    }

    private List<OrganizationView> organizations(UUID tenantId) {
        return jdbc.query(organizationQuery() + " order by o.hierarchy_level, o.created_at, o.code",
                (result, row) -> mapOrganization(result), tenantId);
    }

    private OrganizationView organization(UUID tenantId, UUID organizationId) {
        return jdbc.query(organizationQuery() + " and o.id=?",
                (result, row) -> mapOrganization(result), tenantId, organizationId)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("Organization does not exist"));
    }

    private static String organizationQuery() {
        return """
                select o.id, o.parent_id, o.code, o.name, o.organization_type, o.hierarchy_level,
                       o.contact_name, o.contact_mobile, o.status, o.version,
                       (select count(*) from station s
                         where s.tenant_id=o.tenant_id and s.organization_id=o.id and s.status<>'CLOSED') station_count,
                       (select count(*) from device d join station s
                               on s.tenant_id=d.tenant_id and s.id=d.station_id
                         where d.tenant_id=o.tenant_id and s.organization_id=o.id and d.status<>'RETIRED') device_count,
                       (select count(*) from connector c
                               join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                               join station s on s.tenant_id=d.tenant_id and s.id=d.station_id
                         where c.tenant_id=o.tenant_id and s.organization_id=o.id and c.status<>'DISABLED') connector_count,
                       (select count(distinct co.customer_id) from charging_order co
                               join connector c on c.tenant_id=co.tenant_id and c.id=co.connector_id
                               join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                               join station s on s.tenant_id=d.tenant_id and s.id=d.station_id
                         where co.tenant_id=o.tenant_id and s.organization_id=o.id) customer_count
                  from operator_organization o
                 where o.tenant_id=?
                """;
    }

    private static OrganizationView mapOrganization(java.sql.ResultSet result) throws java.sql.SQLException {
        return new OrganizationView(
                result.getObject("id", UUID.class), result.getObject("parent_id", UUID.class),
                result.getString("code"), result.getString("name"), result.getString("organization_type"),
                result.getInt("hierarchy_level"), result.getString("contact_name"),
                result.getString("contact_mobile"), result.getString("status"), result.getLong("version"),
                result.getLong("station_count"), result.getLong("device_count"),
                result.getLong("connector_count"), result.getLong("customer_count"));
    }

    private static void requireType(Set<String> allowed, String type) {
        if (!allowed.contains(type)) throw new IllegalArgumentException("Invalid organization type");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    record CreateOrganizationRequest(
            @NotNull UUID parentId,
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,64}") String code,
            @NotBlank @Size(max = 160) String name,
            @NotBlank String organizationType,
            @Size(max = 120) String contactName,
            @Size(max = 32) String contactMobile) { }

    record UpdateOrganizationRequest(
            @NotBlank @Size(max = 160) String name,
            @NotBlank String organizationType,
            @Size(max = 120) String contactName,
            @Size(max = 32) String contactMobile,
            @NotBlank String status,
            long version) { }

    record OrganizationView(UUID id, UUID parentId, String code, String name, String organizationType,
                            int hierarchyLevel, String contactName, String contactMobile, String status,
                            long version, long stationCount, long deviceCount, long connectorCount,
                            long customerCount) { }
    record OrganizationTotals(long stationCount, long deviceCount, long connectorCount, long customerCount) { }
    record OrganizationTreeView(OrganizationView root, List<OrganizationView> children,
                                OrganizationTotals totals) { }
}
