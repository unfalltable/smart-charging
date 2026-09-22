package io.smartcharge.platform.gateway.security;

import io.smartcharge.platform.gateway.GatewayProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
final class RedisNonceGuard implements NonceGuard {
    private final StringRedisTemplate redis;
    private final GatewayProperties properties;

    RedisNonceGuard(StringRedisTemplate redis, GatewayProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    @Override
    public boolean claim(String deviceCode, String nonce) {
        Boolean claimed = redis.opsForValue().setIfAbsent(
                "device-nonce:" + deviceCode + ":" + nonce, "1", properties.nonceTtl());
        return Boolean.TRUE.equals(claimed);
    }
}
