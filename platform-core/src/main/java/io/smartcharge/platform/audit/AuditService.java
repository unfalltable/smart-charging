package io.smartcharge.platform.audit;

import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.shared.web.RequestContext;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public final class AuditService {
    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String resourceType, Object resourceId, Object before, Object after) {
        jdbc.update("""
                insert into audit_log
                    (id, tenant_id, actor_subject, action, resource_type, resource_id,
                     request_id, source_ip, before_data, after_data)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as inet), cast(? as jsonb), cast(? as jsonb))
                """, UUID.randomUUID(), TenantContext.requireTenantId(), actorSubject(), action, resourceType,
                resourceId == null ? null : resourceId.toString(), RequestContext.requestId(),
                RequestContext.sourceIp(), toJson(before), toJson(after));
    }

    private String actorSubject() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt) return jwt.getToken().getSubject();
        if (authentication != null && authentication.getName() != null) return authentication.getName();
        return "local-system";
    }

    private String toJson(Object value) {
        if (value == null) return null;
        try {
            return json.writeValueAsString(value);
        } catch (Exception serializationFailure) {
            throw new IllegalArgumentException("Audit data cannot be serialized", serializationFailure);
        }
    }
}
