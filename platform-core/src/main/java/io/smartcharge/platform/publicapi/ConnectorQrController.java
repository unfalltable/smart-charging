package io.smartcharge.platform.publicapi;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class ConnectorQrController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final QrTokenVerifier tokens;
    private final AuditService audit;

    ConnectorQrController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, QrTokenVerifier tokens,
                          AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.tokens = tokens;
        this.audit = audit;
    }

    @PostMapping("/api/v1/admin/assets/connectors/{connectorId}/qr")
    QrResult generate(@PathVariable UUID connectorId) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            String tenantCode = jdbc.query("""
                    select t.code from tenant t
                      join connector c on c.tenant_id=t.id
                      join device d on d.tenant_id=c.tenant_id and d.id=c.device_id
                     where t.id=? and c.id=? and t.status='ACTIVE' and d.status<>'RETIRED'
                    """, (row, index) -> row.getString(1), tenantId, connectorId).stream().findFirst()
                    .orElseThrow(() -> new DomainException("充电端口不存在或设备已退役"));
            audit.record("CONNECTOR_QR_GENERATED", "connector", connectorId, null,
                    Map.of("tenantCode", tenantCode));
            return new QrResult(tokens.issue(tenantId, connectorId), tenantCode, connectorId);
        });
    }

    record QrResult(String token, String tenantCode, UUID connectorId) { }
}
