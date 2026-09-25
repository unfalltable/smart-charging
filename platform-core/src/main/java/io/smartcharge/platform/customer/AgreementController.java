package io.smartcharge.platform.customer;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class AgreementController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final CurrentCustomer currentCustomer;
    private final AuditService audit;

    AgreementController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc,
                        CurrentCustomer currentCustomer, AuditService audit) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.currentCustomer = currentCustomer;
        this.audit = audit;
    }

    @GetMapping("/api/v1/customer/agreements")
    List<AgreementView> customerAgreements() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select d.id, d.document_code, d.version, d.title, d.content_url, d.content_hash,
                       exists(select 1 from customer_agreement a where a.tenant_id=d.tenant_id
                              and a.document_id=d.id and a.customer_id=?) as accepted
                  from agreement_document d
                 where d.tenant_id=? and d.status='ACTIVE' and d.effective_at<=now()
                 order by d.document_code, d.effective_at desc
                """, (result, row) -> new AgreementView(
                    result.getObject("id", UUID.class), result.getString("document_code"),
                    result.getString("version"), result.getString("title"), result.getString("content_url"),
                    result.getString("content_hash"), result.getBoolean("accepted")), customerId, tenantId));
    }

    @PostMapping("/api/v1/customer/agreements/{documentId}/accept")
    Map<String, Object> accept(@PathVariable UUID documentId, @Valid @RequestBody AcceptAgreementRequest request) {
        if (!Set.of("WECHAT", "ALIPAY", "WEB").contains(request.source())) {
            throw new IllegalArgumentException("Invalid agreement source");
        }
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> {
            int inserted = jdbc.update("""
                    insert into customer_agreement (id, tenant_id, customer_id, document_id, source)
                    select ?, ?, ?, d.id, ? from agreement_document d
                     where d.tenant_id=? and d.id=? and d.status='ACTIVE' and d.effective_at<=now()
                    on conflict do nothing
                    """, UUID.randomUUID(), tenantId, customerId, request.source(), tenantId, documentId);
            if (inserted == 0) {
                boolean accepted = Boolean.TRUE.equals(jdbc.queryForObject("""
                        select exists(select 1 from customer_agreement where tenant_id=? and customer_id=? and document_id=?)
                        """, Boolean.class, tenantId, customerId, documentId));
                if (!accepted) throw new IllegalArgumentException("Agreement document is unavailable");
            }
            return Map.of("documentId", documentId, "accepted", true);
        });
    }

    @GetMapping("/api/v1/admin/legal/agreements")
    List<AdminAgreementView> adminAgreements() {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, document_code, version, title, content_url, content_hash, status, effective_at
                  from agreement_document where tenant_id=? order by created_at desc
                """, (result, row) -> new AdminAgreementView(
                    result.getObject("id", UUID.class), result.getString("document_code"),
                    result.getString("version"), result.getString("title"), result.getString("content_url"),
                    result.getString("content_hash"), result.getString("status"),
                    result.getTimestamp("effective_at").toInstant()), tenantId));
    }

    @PostMapping("/api/v1/admin/legal/agreements")
    @ResponseStatus(HttpStatus.CREATED)
    AdminAgreementView createAgreement(@Valid @RequestBody CreateAgreementRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        return tenantJdbc.readWrite(() -> {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    update agreement_document set status='EXPIRED'
                     where tenant_id=? and document_code=? and status='ACTIVE'
                    """, tenantId, request.documentCode());
            jdbc.update("""
                    insert into agreement_document
                        (id, tenant_id, document_code, version, title, content_url,
                         content_hash, status, effective_at)
                    values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, id, tenantId, request.documentCode(), request.version(), request.title(),
                    request.contentUrl(), request.contentHash(), JdbcTimes.timestamp(request.effectiveAt()));
            AdminAgreementView view = new AdminAgreementView(id, request.documentCode(), request.version(),
                    request.title(), request.contentUrl(), request.contentHash(), "ACTIVE", request.effectiveAt());
            audit.record("AGREEMENT_PUBLISHED", "agreement_document", id, null, view);
            return view;
        });
    }

    record AcceptAgreementRequest(@NotBlank String source) { }
    record CreateAgreementRequest(@NotBlank @Size(max = 64) String documentCode,
                                  @NotBlank @Size(max = 32) String version,
                                  @NotBlank @Size(max = 200) String title,
                                  @NotBlank @Size(max = 500) @Pattern(regexp = "https://.+") String contentUrl,
                                  @NotBlank @Pattern(regexp = "[A-Fa-f0-9]{64}") String contentHash,
                                  Instant effectiveAt) {
        CreateAgreementRequest {
            if (effectiveAt == null) effectiveAt = Instant.now();
        }
    }
    record AgreementView(UUID id, String documentCode, String version, String title,
                         String contentUrl, String contentHash, boolean accepted) { }
    record AdminAgreementView(UUID id, String documentCode, String version, String title,
                              String contentUrl, String contentHash, String status, Instant effectiveAt) { }
}
