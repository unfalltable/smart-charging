package io.smartcharge.platform.gateway.security;

public interface NonceGuard {
    boolean claim(String deviceCode, String nonce);
}
