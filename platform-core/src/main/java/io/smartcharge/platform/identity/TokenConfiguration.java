package io.smartcharge.platform.identity;

import com.nimbusds.jwt.JWTParser;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;

@Configuration
@Profile("!local")
@EnableConfigurationProperties({TokenProperties.class, WeChatIdentityProperties.class})
class TokenConfiguration {
    @Bean
    SecretKey appJwtSecret(TokenProperties properties) {
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(properties.appJwtSecretBase64());
        } catch (IllegalArgumentException invalidBase64) {
            throw new IllegalStateException("AUTH_JWT_SECRET_BASE64 must be valid Base64", invalidBase64);
        }
        if (secret.length < 32) throw new IllegalStateException("Application JWT secret must contain at least 256 bits");
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtEncoder appJwtEncoder(SecretKey secret) {
        return NimbusJwtEncoder.withSecretKey(secret).algorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey secret, TokenProperties properties) {
        NimbusJwtDecoder application = NimbusJwtDecoder.withSecretKey(secret).macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience().contains(properties.apiAudience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new org.springframework.security.oauth2.core.OAuth2Error(
                        "invalid_token", "Required audience is missing", null));
        application.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.appIssuer()), audience));
        NimbusJwtDecoder oidc = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(properties.oidcIssuerUri());
        oidc.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.oidcIssuerUri()), audience));
        return token -> {
            try {
                String issuer = JWTParser.parse(token).getJWTClaimsSet().getIssuer();
                return properties.appIssuer().equals(issuer) ? application.decode(token) : oidc.decode(token);
            } catch (JwtException expected) {
                throw expected;
            } catch (Exception malformed) {
                throw new JwtException("Malformed JWT", malformed);
            }
        };
    }
}
