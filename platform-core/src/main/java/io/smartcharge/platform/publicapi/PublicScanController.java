package io.smartcharge.platform.publicapi;

import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
final class PublicScanController {
    private final QrTokenVerifier tokens;
    private final TenantJdbcExecutor tenantJdbc;
    private final JdbcTemplate jdbc;

    PublicScanController(QrTokenVerifier tokens, TenantJdbcExecutor tenantJdbc, JdbcTemplate jdbc) {
        this.tokens = tokens;
        this.tenantJdbc = tenantJdbc;
        this.jdbc = jdbc;
    }

    @GetMapping("/scan/{token}")
    ScanResult scan(@PathVariable String token) {
        QrTokenVerifier.VerifiedQr verified = tokens.verify(token);
        return tenantJdbc.readWriteAs(verified.tenantId(), () -> jdbc.query("""
                select c.id, s.name as station_name, d.device_code, c.connector_no,
                       case when c.status='AVAILABLE' and (d.status<>'ONLINE' or d.last_seen_at is null
                                 or d.last_seen_at<now()-interval '3 minutes') then 'OFFLINE' else c.status end as status,
                       tenant.code as tenant_code, t.id as tariff_id, t.billing_mode,
                       coalesce((t.price_rules ->> 'durationUnitPriceMinor')::bigint,
                                (t.price_rules ->> 'unitPriceMinor')::bigint, 0) as duration_price_minor,
                       coalesce((t.price_rules ->> 'energyUnitPriceMinor')::bigint,
                                (t.price_rules ->> 'unitPriceMinor')::bigint, 0) as energy_price_minor,
                       coalesce((t.price_rules ->> 'minimumAmountMinor')::bigint, 0) as minimum_amount_minor
                  from connector c
                  join device d on d.id = c.device_id and d.tenant_id = c.tenant_id
                  join station s on s.id = d.station_id and s.tenant_id = c.tenant_id
                  join tariff t on t.id = c.tariff_id and t.tenant_id = c.tenant_id
                  join tenant on tenant.id=c.tenant_id and tenant.status='ACTIVE'
                 where c.tenant_id = ? and c.id = ? and s.status = 'ACTIVE'
                   and d.status <> 'RETIRED' and t.status = 'ACTIVE'
                   and t.effective_from<=now() and (t.effective_until is null or t.effective_until>now())
                """, (result, row) -> new ScanResult(
                        result.getObject("id", UUID.class), result.getString("station_name"),
                        result.getString("device_code"), result.getInt("connector_no"),
                        result.getString("status"), result.getObject("tariff_id", UUID.class),
                        priceText(result.getString("billing_mode"), result.getLong("duration_price_minor"),
                                result.getLong("energy_price_minor"), result.getLong("minimum_amount_minor")),
                        verified.tenantId(), result.getString("tenant_code")),
                verified.tenantId(), verified.connectorId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Charging connector is unavailable")));
    }

    static String priceText(String billingMode, long durationMinor, long energyMinor, long minimumMinor) {
        String price = switch (billingMode) {
            case "DURATION" -> durationMinor + " 分/分钟";
            case "ENERGY" -> energyMinor + " 分/Wh";
            case "HYBRID" -> durationMinor + " 分/分钟 + " + energyMinor + " 分/Wh";
            default -> throw new IllegalArgumentException("Unsupported billing mode");
        };
        return minimumMinor > 0 ? price + "，最低 " + minimumMinor + " 分" : price;
    }

    record ScanResult(UUID id, String stationName, String deviceCode, int connectorNo,
                      String status, UUID tariffId, String priceText, UUID tenantId, String tenantCode) { }
}
