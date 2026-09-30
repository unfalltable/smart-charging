package io.smartcharge.platform.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.smartcharge.platform.DatabaseTestSupport;
import org.junit.jupiter.api.Test;

class PublicScanIntegrationTest {
    @Test
    void signedPhysicalQrResolvesTheActualTariffAndOfflineState() throws Exception {
        var db = DatabaseTestSupport.database();
        var fixture = db.seedCharging();
        var runtime = db.runtimeJdbc();
        var signing = new QrTokenVerifier("isolated-verification-qr-secret-32-characters");
        var controller = new PublicScanController(signing, runtime.tenants(), runtime.jdbc());
        String token = signing.issue(fixture.tenantId(), fixture.connectorId());
        var result = controller.scan(token);
        assertThat(result.id()).isEqualTo(fixture.connectorId());
        assertThat(result.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(result.status()).isEqualTo("AVAILABLE");
        assertThat(result.priceText()).isEqualTo("2 分/分钟");
        db.ownerJdbc().update("update device set last_seen_at=now()-interval '10 minutes' where id=?", fixture.deviceId());
        assertThat(controller.scan(token).status()).isEqualTo("OFFLINE");
        db.ownerJdbc().update("update tenant set status='SUSPENDED' where id=?", fixture.tenantId());
        assertThatThrownBy(() -> controller.scan(token)).isInstanceOf(IllegalArgumentException.class);
    }
}
