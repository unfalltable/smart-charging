package io.smartcharge.platform.finance;

import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class MerchantChannelRepository {
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;

    MerchantChannelRepository(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
    }

    Configuration requireActive(UUID tenantId, String channel) {
        return tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                select id, merchant_id, application_id, secret_reference, notify_url,
                       refund_notify_url, version
                  from merchant_channel
                 where tenant_id=? and channel=? and status='ACTIVE'
                """, (result, row) -> new Configuration(
                result.getObject("id", UUID.class), tenantId, channel, result.getString("merchant_id"),
                result.getString("application_id"), result.getString("secret_reference"),
                result.getString("notify_url"), result.getString("refund_notify_url"),
                result.getLong("version")), tenantId, channel).stream().findFirst()
                .orElseThrow(() -> new DomainException(channel + " merchant channel is not active")));
    }

    record Configuration(UUID id, UUID tenantId, String channel, String merchantId, String applicationId,
                         String secretReference, String notifyUrl, String refundNotifyUrl, long version) { }
}
