package io.smartcharge.platform.gateway.security;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "gateway.credentials", name = "provider", havingValue = "redis")
final class RedisEncryptedCredentialProvider implements DeviceCredentialProvider {
    private static final String PREFIX = "device:credential:v1:";
    private final StringRedisTemplate redis;
    private final SecretKeySpec masterKey;
    private final Duration cacheTtl;
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();

    RedisEncryptedCredentialProvider(StringRedisTemplate redis, CredentialStoreProperties properties) {
        this.redis = redis;
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.masterKeyBase64());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Device credential master key is not valid Base64", invalid);
        }
        if (decoded.length != 32) throw new IllegalArgumentException("Device credential master key must be 32 bytes");
        this.masterKey = new SecretKeySpec(decoded, "AES");
        this.cacheTtl = properties.cacheTtl() == null ? Duration.ofMinutes(2) : properties.cacheTtl();
    }

    @Override
    public byte[] secretFor(String deviceCode) {
        CachedSecret cached = cache.get(deviceCode);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) return cached.value().clone();
        String encrypted = redis.opsForValue().get(PREFIX + deviceCode);
        if (encrypted == null) throw new DeviceAuthenticationException("Unknown device or invalid credential");
        byte[] secret = decrypt(deviceCode, encrypted);
        if (secret.length < 32) throw new DeviceAuthenticationException("Unknown device or invalid credential");
        cache.put(deviceCode, new CachedSecret(secret.clone(), Instant.now().plus(cacheTtl)));
        return secret;
    }

    private byte[] decrypt(String deviceCode, String encoded) {
        try {
            if (!encoded.startsWith("v1.")) throw new IllegalArgumentException("Unsupported credential envelope");
            byte[] envelope = Base64.getUrlDecoder().decode(encoded.substring(3));
            ByteBuffer buffer = ByteBuffer.wrap(envelope);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] ciphertext = new byte[buffer.remaining()];
            buffer.get(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, masterKey, new GCMParameterSpec(128, nonce));
            cipher.updateAAD(deviceCode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } catch (Exception invalid) {
            throw new DeviceAuthenticationException("Unknown device or invalid credential");
        }
    }

    private record CachedSecret(byte[] value, Instant expiresAt) { }
}
