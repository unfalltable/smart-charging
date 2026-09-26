package io.smartcharge.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.security")
record TokenProperties(String appIssuer, String appJwtSecretBase64, String oidcIssuerUri, String oidcJwkSetUri,
                       String apiAudience, long accessTokenMinutes, long refreshTokenDays) { }
