package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.persistence.JdbcTimes;
import io.smartcharge.platform.shared.domain.AuthenticationFailureException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
final class AdminTokenService {
    private final JdbcTemplate jdbc;
    private final JwtEncoder encoder;
    private final TokenProperties properties;
    private final SecureRandom random = new SecureRandom();

    AdminTokenService(JdbcTemplate jdbc, JwtEncoder encoder, TokenProperties properties) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.properties = properties;
    }

    Session issue(Account account, boolean passwordChangeOnly) {
        return issue(account, passwordChangeOnly, null, null);
    }

    Session rotate(String refreshToken) {
        String hash = sha256(refreshToken);
        RefreshRecord current = jdbc.query("""
                select id, user_id, family_id, revoked_at, expires_at
                  from admin_refresh_token where token_hash=? for update
                """, (result, row) -> new RefreshRecord(
                result.getObject("id", UUID.class), result.getObject("user_id", UUID.class),
                result.getObject("family_id", UUID.class),
                result.getTimestamp("revoked_at") == null ? null : result.getTimestamp("revoked_at").toInstant(),
                result.getTimestamp("expires_at").toInstant()), hash).stream().findFirst()
                .orElseThrow(() -> new AuthenticationFailureException("登录状态已失效，请重新登录"));
        if (current.revokedAt() != null) {
            jdbc.update("update admin_refresh_token set revoked_at=coalesce(revoked_at,now()) where family_id=?",
                    current.familyId());
            jdbc.update("update platform_user set auth_version=auth_version+1, updated_at=now() where id=?",
                    current.userId());
            throw new AuthenticationFailureException("检测到登录凭据重复使用，所有会话已撤销");
        }
        if (!current.expiresAt().isAfter(Instant.now())) {
            jdbc.update("update admin_refresh_token set revoked_at=now() where id=?", current.id());
            throw new AuthenticationFailureException("登录状态已过期，请重新登录");
        }
        Account account = loadActive(current.userId());
        return issue(account, account.mustChangePassword(), current.id(), current.familyId());
    }

    void revoke(UUID userId, String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            jdbc.update("""
                    update admin_refresh_token set revoked_at=coalesce(revoked_at,now()), last_used_at=now()
                     where user_id=? and family_id=(
                        select family_id from admin_refresh_token where token_hash=? and user_id=?
                     )
                    """, userId, sha256(refreshToken), userId);
        }
        jdbc.update("update platform_user set auth_version=auth_version+1, updated_at=now() where id=?", userId);
    }

    void revokeAll(UUID userId) {
        jdbc.update("update admin_refresh_token set revoked_at=coalesce(revoked_at,now()) where user_id=?", userId);
    }

    Account loadActive(UUID userId) {
        return jdbc.query("""
                select id, subject, username, display_name, status, platform_role,
                       must_change_password, auth_version
                  from platform_user where id=? and status='ACTIVE'
                """, (result, row) -> new Account(
                result.getObject("id", UUID.class), result.getString("subject"),
                result.getString("username"), result.getString("display_name"),
                result.getString("platform_role"), result.getBoolean("must_change_password"),
                result.getLong("auth_version")), userId).stream().findFirst()
                .orElseThrow(() -> new AuthenticationFailureException("账号已停用，请联系管理员"));
    }

    private Session issue(Account account, boolean passwordChangeOnly,
                          UUID replacedTokenId, UUID existingFamilyId) {
        Instant now = Instant.now();
        long accessMinutes = properties.accessTokenMinutes() > 0 ? properties.accessTokenMinutes() : 15;
        long refreshDays = properties.refreshTokenDays() > 0 ? properties.refreshTokenDays() : 30;
        Authority authority = authority(account, passwordChangeOnly);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.appIssuer())
                .subject(account.subject())
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofMinutes(accessMinutes)))
                .id(UUID.randomUUID().toString())
                .audience(List.of(properties.apiAudience()))
                .claim("scope", String.join(" ", authority.scopes()))
                .claim("tenant_ids", authority.tenantIds().stream().map(UUID::toString).toList())
                .claim("preferred_username", account.username())
                .claim("name", account.displayName())
                .claim("admin_user_id", account.id().toString())
                .claim("auth_version", account.authVersion())
                .build();
        String accessToken = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims)).getTokenValue();
        if (passwordChangeOnly) {
            return new Session(accessToken, null, accessMinutes * 60, true);
        }
        byte[] refreshBytes = new byte[32];
        random.nextBytes(refreshBytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(refreshBytes);
        UUID refreshId = UUID.randomUUID();
        UUID familyId = existingFamilyId == null ? refreshId : existingFamilyId;
        jdbc.update("""
                insert into admin_refresh_token (id, user_id, token_hash, family_id, expires_at)
                values (?, ?, ?, ?, ?)
                """, refreshId, account.id(), sha256(refreshToken), familyId,
                JdbcTimes.timestamp(now.plus(Duration.ofDays(refreshDays))));
        if (replacedTokenId != null) {
            jdbc.update("""
                    update admin_refresh_token set revoked_at=now(), replaced_by=?, last_used_at=now()
                     where id=? and revoked_at is null
                    """, refreshId, replacedTokenId);
        }
        return new Session(accessToken, refreshToken, accessMinutes * 60, false);
    }

    private Authority authority(Account account, boolean passwordChangeOnly) {
        if (passwordChangeOnly) return new Authority(Set.of("password_change"), List.of());
        LinkedHashSet<String> scopes = new LinkedHashSet<>();
        if ("PLATFORM_SUPER_ADMIN".equals(account.platformRole())) {
            scopes.addAll(List.of("platform_admin", "admin", "operator", "finance", "auditor", "support"));
        }
        List<TenantRole> tenantRoles = jdbc.query("""
                select a.tenant_id, a.role_code
                 from user_tenant_authority a
                  join tenant t on t.id=a.tenant_id and t.status='ACTIVE'
                 where a.user_id=? and a.status='ACTIVE'
                 order by a.tenant_id, a.role_code
                """, (result, row) -> new TenantRole(
                result.getObject("tenant_id", UUID.class), result.getString("role_code")), account.id());
        List<UUID> tenantIds = new ArrayList<>();
        for (TenantRole role : tenantRoles) {
            if (!tenantIds.contains(role.tenantId())) tenantIds.add(role.tenantId());
            scopes.add(scopeForRole(role.roleCode()));
        }
        if (scopes.isEmpty()) throw new AuthenticationFailureException("账号没有可用的平台或租户权限");
        return new Authority(Set.copyOf(scopes), List.copyOf(tenantIds));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static String scopeForRole(String roleCode) {
        return switch (roleCode) {
            case "TENANT_ADMIN" -> "admin";
            case "OPERATOR" -> "operator";
            case "FINANCE" -> "finance";
            case "AUDITOR" -> "auditor";
            case "SUPPORT" -> "support";
            default -> throw new AuthenticationFailureException("账号包含不支持的岗位角色");
        };
    }

    record Account(UUID id, String subject, String username, String displayName,
                   String platformRole, boolean mustChangePassword, long authVersion) { }
    record Session(String accessToken, String refreshToken, long expiresInSeconds, boolean mustChangePassword) { }
    private record RefreshRecord(UUID id, UUID userId, UUID familyId, Instant revokedAt, Instant expiresAt) { }
    private record TenantRole(UUID tenantId, String roleCode) { }
    private record Authority(Set<String> scopes, List<UUID> tenantIds) { }
}
