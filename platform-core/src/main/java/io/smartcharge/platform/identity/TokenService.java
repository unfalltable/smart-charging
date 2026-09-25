package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.persistence.JdbcTimes;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
final class TokenService {
    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final SecureRandom random = new SecureRandom();

    TokenService(JdbcTemplate jdbc, JwtEncoder encoder, TokenProperties properties) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.properties = properties;
    }

    Session issue(UUID tenantId, UUID customerId) {
        return issue(tenantId, customerId, null, null);
    }

    Session rotate(UUID tenantId, String refreshToken) {
        String hash = sha256(refreshToken);
        RefreshRecord current = jdbc.query("""
                select id, customer_id, family_id, revoked_at, expires_at from auth_refresh_token
                 where tenant_id=? and token_hash=? for update
                """, (result, row) -> new RefreshRecord(
                result.getObject("id", UUID.class), result.getObject("customer_id", UUID.class),
                result.getObject("family_id", UUID.class), result.getTimestamp("revoked_at") == null ? null
                        : result.getTimestamp("revoked_at").toInstant(),
                result.getTimestamp("expires_at").toInstant()), tenantId, hash)
                .stream().findFirst().orElseThrow(() -> new DomainException("Refresh token is invalid or expired"));
        if (current.revokedAt() != null) {
            jdbc.update("update auth_refresh_token set revoked_at=coalesce(revoked_at,now()) where tenant_id=? and family_id=?",
                    tenantId, current.familyId());
            throw new DomainException("Refresh token reuse was detected; the session has been revoked");
        }
        if (!current.expiresAt().isAfter(Instant.now())) throw new DomainException("Refresh token is invalid or expired");
        return issue(tenantId, current.customerId(), current.id(), current.familyId());
    }

    void revoke(UUID tenantId, String refreshToken) {
        jdbc.update("""
                update auth_refresh_token set revoked_at=coalesce(revoked_at,now()), last_used_at=now()
                 where tenant_id=? and family_id=(
                    select family_id from auth_refresh_token where tenant_id=? and token_hash=?
                 )
                """, tenantId, tenantId, sha256(refreshToken));
    }

    private Session issue(UUID tenantId, UUID customerId, UUID replacedTokenId, UUID existingFamilyId) {
        Instant now = Instant.now();
        long accessMinutes = properties.accessTokenMinutes() > 0 ? properties.accessTokenMinutes() : 15;
        long refreshDays = properties.refreshTokenDays() > 0 ? properties.refreshTokenDays() : 30;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.appIssuer())
                .subject("customer:" + customerId)
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(accessMinutes)))
                .id(UUID.randomUUID().toString())
                .audience(List.of(properties.apiAudience()))
                .claim("tenant_ids", List.of(tenantId.toString()))
                .claim("customer_id", customerId.toString())
                .claim("scope", "customer")
                .build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        byte[] refreshBytes = new byte[32];
        random.nextBytes(refreshBytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(refreshBytes);
        UUID refreshId = UUID.randomUUID();
        UUID familyId = existingFamilyId == null ? refreshId : existingFamilyId;
        jdbc.update("""
                insert into auth_refresh_token
                    (id, tenant_id, customer_id, token_hash, expires_at, family_id)
                values (?, ?, ?, ?, ?, ?)
                """, refreshId, tenantId, customerId, sha256(refreshToken),
                JdbcTimes.timestamp(now.plus(Duration.ofDays(refreshDays))), familyId);
        if (replacedTokenId != null) {
            jdbc.update("""
                    update auth_refresh_token set revoked_at=now(), replaced_by=?, last_used_at=now()
                     where tenant_id=? and id=? and revoked_at is null
                    """, refreshId, tenantId, replacedTokenId);
        }
        return new Session(accessToken, refreshToken, accessMinutes * 60, tenantId, customerId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    record RefreshRecord(UUID id, UUID customerId, UUID familyId, Instant revokedAt, Instant expiresAt) { }
    record Session(String accessToken, String refreshToken, long expiresInSeconds,
                   UUID tenantId, UUID customerId) { }
}
