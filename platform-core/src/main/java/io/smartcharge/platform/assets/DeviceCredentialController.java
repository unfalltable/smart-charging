package io.smartcharge.platform.assets;

import io.smartcharge.platform.audit.AuditService;
import io.smartcharge.platform.tenancy.TenantContext;
import io.smartcharge.platform.tenancy.TenantJdbcExecutor;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/assets/devices")
final class DeviceCredentialController {
    private static final String PREFIX = "device:credential:v1:";
    private final JdbcTemplate jdbc;
    private final TenantJdbcExecutor tenantJdbc;
    private final StringRedisTemplate redis;
    private final AuditService audit;
    private final SecretKeySpec masterKey;
    private final SecureRandom random = new SecureRandom();

    DeviceCredentialController(JdbcTemplate jdbc, TenantJdbcExecutor tenantJdbc, StringRedisTemplate redis,
                               AuditService audit,
                               @Value("${charging.device-credentials.master-key-base64:}") String masterKeyBase64) {
        this.jdbc = jdbc;
        this.tenantJdbc = tenantJdbc;
        this.redis = redis;
        this.audit = audit;
        byte[] decoded = masterKeyBase64.isBlank() ? new byte[0] : Base64.getDecoder().decode(masterKeyBase64);
        this.masterKey = decoded.length == 32 ? new SecretKeySpec(decoded, "AES") : null;
    }

    @PostMapping("/{deviceId}/credential/rotate")
    ResponseEntity<CredentialIssued> rotate(@PathVariable UUID deviceId) {
        if (masterKey == null) throw new IllegalStateException("Device credential master key is not configured");
        UUID tenantId = TenantContext.requireTenantId();
        Device device = tenantJdbc.readWrite(() -> jdbc.query("""
                select device_code, credential_fingerprint from device
                 where tenant_id=? and id=? and status<>'RETIRED' for update
                """, (result, row) -> new Device(result.getString("device_code"),
                result.getString("credential_fingerprint")), tenantId, deviceId).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Active device does not exist")));
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String issued = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String fingerprint = sha256(secret);
        redis.opsForValue().set(PREFIX + device.deviceCode(), encrypt(device.deviceCode(), secret));
        tenantJdbc.readWrite(() -> {
            jdbc.update("""
                    update device set credential_fingerprint=?, updated_at=now(), version=version+1
                     where tenant_id=? and id=?
                    """, fingerprint, tenantId, deviceId);
            audit.record("DEVICE_CREDENTIAL_ROTATED", "device", deviceId,
                    Map.of("fingerprint", device.fingerprint() == null ? "" : device.fingerprint()),
                    Map.of("fingerprint", fingerprint));
            return null;
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CredentialIssued(device.deviceCode(), issued, fingerprint));
    }

    private String encrypt(String deviceCode, byte[] secret) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, masterKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(deviceCode.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(secret);
            return "v1." + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                            .put(nonce).put(ciphertext).array());
        } catch (Exception unavailable) {
            throw new IllegalStateException("Device credential encryption failed", unavailable);
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record Device(String deviceCode, String fingerprint) { }
    record CredentialIssued(String deviceCode, String secret, String fingerprint) { }
}
