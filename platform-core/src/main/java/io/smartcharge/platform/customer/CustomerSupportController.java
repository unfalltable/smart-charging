package io.smartcharge.platform.customer;

import io.smartcharge.platform.identity.CurrentCustomer;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customer/support")
final class CustomerSupportController {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final CurrentCustomer currentCustomer;

    CustomerSupportController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, CurrentCustomer currentCustomer) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.currentCustomer = currentCustomer;
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    TicketView create(@Valid @RequestBody CreateTicketRequest request) {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> {
            UUID id = UUID.randomUUID();
            String number = "CS-" + Instant.now().toEpochMilli() + "-" + id.toString().substring(0, 6);
            jdbc.update("""
                    insert into work_order
                        (id, tenant_id, work_order_no, customer_id, title, description, priority, status)
                    values (?, ?, ?, ?, ?, ?, 'NORMAL', 'OPEN')
                    """, id, tenantId, number, customerId, request.title(), request.description());
            return new TicketView(id, number, request.title(), request.description(), "OPEN", Instant.now());
        });
    }

    @GetMapping("/tickets")
    List<TicketView> list() {
        UUID tenantId = TenantContext.requireTenantId();
        UUID customerId = currentCustomer.requireId();
        return tenantJdbc.readWrite(() -> jdbc.query("""
                select id, work_order_no, title, description, status, created_at
                  from work_order where tenant_id=? and customer_id=? order by created_at desc
                """, (result, row) -> new TicketView(
                    result.getObject("id", UUID.class), result.getString("work_order_no"),
                    result.getString("title"), result.getString("description"), result.getString("status"),
                    result.getTimestamp("created_at").toInstant()), tenantId, customerId));
    }

    record CreateTicketRequest(@NotBlank @Size(max = 200) String title,
                               @NotBlank @Size(max = 1000) String description) { }
    record TicketView(UUID id, String ticketNo, String title, String description, String status, Instant createdAt) { }
}
