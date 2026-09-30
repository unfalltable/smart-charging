package io.smartcharge.platform.finance;

import io.smartcharge.platform.shared.domain.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

final class FinanceIdempotency {
    private static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

    private FinanceIdempotency() { }

    static Request begin(JdbcTemplate jdbc, UUID tenantId, String scope, String key, Object payload) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 8-128 safe characters");
        }
        String hash = fingerprint(payload);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                tenantId + ":" + scope + ":" + key);
        Stored previous = jdbc.query("""
                select request_hash, response_body::text from idempotency_record
                 where tenant_id=? and scope=? and idempotency_key=?
                """, (result, row) -> new Stored(result.getString("request_hash"),
                response(result.getString("response_body"))), tenantId, scope, key).stream().findFirst().orElse(null);
        if (previous != null && !hash.equals(previous.hash())) {
            throw new DomainException("Idempotency key was reused for a different financial operation");
        }
        return new Request(scope, key, hash, previous == null ? null : previous.response());
    }

    static void save(JdbcTemplate jdbc, UUID tenantId, Request request, Map<String, Object> response, int status) {
        jdbc.update("""
                insert into idempotency_record
                    (id, tenant_id, scope, idempotency_key, request_hash, response_status, response_body, expires_at)
                values (?, ?, ?, ?, ?, ?, cast(? as jsonb), now()+interval '10 years')
                """, UUID.randomUUID(), tenantId, request.scope(), request.key(), request.hash(), status, serialize(response));
    }

    private static String fingerprint(Object payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(serialize(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String serialize(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalArgumentException("Financial operation cannot be serialized", invalid); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> response(String value) {
        try { return JSON.readValue(value, Map.class); }
        catch (Exception invalid) { throw new IllegalStateException("Financial idempotency record is invalid", invalid); }
    }

    record Request(String scope, String key, String hash, Map<String, Object> response) { }
    private record Stored(String hash, Map<String, Object> response) { }
}
