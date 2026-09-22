package io.smartcharge.platform.gateway.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gateway.credentials")
public record CredentialStoreProperties(String provider, String masterKeyBase64, Duration cacheTtl) { }
