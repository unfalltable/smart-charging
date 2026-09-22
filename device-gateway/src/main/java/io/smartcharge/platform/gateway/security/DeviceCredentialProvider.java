package io.smartcharge.platform.gateway.security;

public interface DeviceCredentialProvider {
    byte[] secretFor(String deviceCode);
}
