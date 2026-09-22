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
                select c.id, s.name as station_name, d.device_code, c.connector_no, c.status,
                       t.id as tariff_id, t.billing_mode,
                       coalesce(t.price_rules ->> 'unitPriceMinor', '0') as unit_price_minor
                  from connector c
                  join device d on d.id = c.device_id and d.tenant_id = c.tenant_id
                  join station s on s.id = d.station_id and s.tenant_id = c.tenant_id
                  join tariff t on t.id = c.tariff_id and t.tenant_id = c.tenant_id
                 where c.tenant_id = ? and c.id = ? and s.status = 'ACTIVE'
                   and d.status <> 'RETIRED' and t.status = 'ACTIVE'
                """, (result, row) -> new ScanResult(
                        result.getObject("id", UUID.class), result.getString("station_name"),
                        result.getString("device_code"), result.getInt("connector_no"),
                        result.getString("status"), result.getObject("tariff_id", UUID.class),
                        priceText(result.getString("billing_mode"), result.getLong("unit_price_minor"))),
                verified.tenantId(), verified.connectorId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Charging connector is unavailable")));
    }

    private static String priceText(String billingMode, long unitPriceMinor) {
        String unit = switch (billingMode) {
            case "DURATION" -> "分钟";
            case "ENERGY" -> "Wh";
            default -> "计费单位";
        };
        return unitPriceMinor + " 分/" + unit;
    }

    record ScanResult(UUID id, String stationName, String deviceCode, int connectorNo,
                      String status, UUID tariffId, String priceText) { }
}
