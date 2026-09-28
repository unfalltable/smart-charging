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

    Configuration requireById(UUID tenantId, UUID merchantChannelId, String channel) {
        return tenantJdbc.readWriteAs(tenantId, () -> jdbc.query("""
                select id, organization_id, merchant_id, application_id, secret_reference, notify_url,
                       refund_notify_url, profit_sharing_required, version
                  from merchant_channel
                 where tenant_id=? and id=? and channel=?
                """, (result, row) -> new Configuration(
                result.getObject("id", UUID.class), tenantId,
                result.getObject("organization_id", UUID.class), channel, result.getString("merchant_id"),
                result.getString("application_id"), result.getString("secret_reference"),
                result.getString("notify_url"), result.getString("refund_notify_url"),
                result.getBoolean("profit_sharing_required"), result.getLong("version")),
                tenantId, merchantChannelId, channel)
                .stream().findFirst()
                .orElseThrow(() -> new DomainException(channel + " organization merchant channel is unavailable")));
    }

    record Configuration(UUID id, UUID tenantId, UUID organizationId, String channel,
                         String merchantId, String applicationId,
                         String secretReference, String notifyUrl, String refundNotifyUrl,
                         boolean profitSharingRequired, long version) { }
}
