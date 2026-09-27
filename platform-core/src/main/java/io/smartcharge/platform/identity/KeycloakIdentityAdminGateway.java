package io.smartcharge.platform.identity;

import io.smartcharge.platform.shared.domain.DomainException;
import io.smartcharge.platform.shared.domain.ServiceUnavailableException;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(prefix = "charging.security", name = "identity-provider-mode", havingValue = "bundled")
final class KeycloakIdentityAdminGateway implements IdentityAdminGateway {
    private static final Logger LOG = LoggerFactory.getLogger(KeycloakIdentityAdminGateway.class);
    private static final char[] PASSWORD_CHARS =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%&*+-_".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final IdentityAdminProperties properties;
    private final RestClient http;

    KeycloakIdentityAdminGateway(IdentityAdminProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.http = builder.baseUrl(required(properties.getBaseUri(), "identity admin base URI")).build();
        required(properties.getRealm(), "identity realm");
        required(properties.getClientId(), "identity admin client id");
        required(properties.getClientSecret(), "identity admin client secret");
    }

    @Override
    public Capabilities capabilities() {
        return new Capabilities(true, properties.isEmailDeliveryEnabled(), true,
                properties.getInvitationLifespanHours());
    }

    @Override
    public ProvisionedIdentity provision(ProvisionIdentity request) {
        String username = request.username().strip().toLowerCase(Locale.ROOT);
        String email = request.email().strip().toLowerCase(Locale.ROOT);
        Map<String, Object> existing = findExact("username", username);
        if (existing == null) existing = findExact("email", email);
        if (existing != null) {
            String existingEmail = string(existing.get("email"));
            if (!existingEmail.isBlank() && !existingEmail.equalsIgnoreCase(email)) {
                throw new DomainException("The username is already assigned to a different email address");
            }
            String subject = string(existing.get("id"));
            Map<String, Object> profileUpdate = new LinkedHashMap<>();
            if (existingEmail.isBlank()) profileUpdate.put("email", email);
            if (!request.displayName().isBlank()) profileUpdate.put("firstName", request.displayName().strip());
            if (!profileUpdate.isEmpty()) updateUser(subject, profileUpdate);
            addTenantAuthorization(subject, request.tenantId(), request.roleCode(), request.requireMfa());
            return new ProvisionedIdentity(subject, string(existing.get("username")), email, false, null);
        }

        String temporaryPassword = newTemporaryPassword();
        List<String> requiredActions = new ArrayList<>();
        requiredActions.add("UPDATE_PASSWORD");
        if (request.requireMfa()) requiredActions.add("CONFIGURE_TOTP");
        if (properties.isEmailDeliveryEnabled()) requiredActions.add("VERIFY_EMAIL");
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("email", email);
        user.put("firstName", request.displayName().strip());
        user.put("enabled", true);
        user.put("emailVerified", false);
        user.put("requiredActions", requiredActions);
        user.put("attributes", Map.of("tenant_ids", List.of(request.tenantId().toString())));
        user.put("credentials", List.of(Map.of(
                "type", "password", "value", temporaryPassword, "temporary", true)));

        String subject = null;
        try {
            URI location = http.post()
                    .uri(adminPath("/users"))
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(user)
                    .retrieve()
                    .toBodilessEntity()
                    .getHeaders()
                    .getLocation();
            subject = location == null ? null : location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
            if (subject == null || subject.isBlank()) {
                Map<String, Object> created = findExact("username", username);
                subject = created == null ? null : string(created.get("id"));
            }
            if (subject == null || subject.isBlank()) {
                throw new ServiceUnavailableException("Identity provider did not return the created user id");
            }
            addRealmRole(subject, IdentityAdminGateway.realmRole(request.roleCode()));
            return new ProvisionedIdentity(subject, username, email, true,
                    properties.isEmailDeliveryEnabled() ? null : temporaryPassword);
        } catch (RestClientResponseException exception) {
            if (subject != null) deleteUser(subject);
            if (exception.getStatusCode().value() == 409) {
                throw new DomainException("The username or email address already exists");
            }
            throw unavailable("Identity account creation failed", exception);
        } catch (RuntimeException exception) {
            if (subject != null) deleteUser(subject);
            throw exception;
        }
    }

    @Override
    public void addTenantAuthorization(String subject, UUID tenantId, String roleCode, boolean requireMfa) {
        try {
            Map<String, Object> user = getUser(subject);
            Map<String, Object> attributes = mutableAttributes(user.get("attributes"));
            LinkedHashSet<String> tenantIds = stringSet(attributes.get("tenant_ids"));
            tenantIds.add(tenantId.toString());
            attributes.put("tenant_ids", List.copyOf(tenantIds));
            Map<String, Object> update = new LinkedHashMap<>();
            update.put("attributes", attributes);
            if (requireMfa) {
                LinkedHashSet<String> actions = stringSet(user.get("requiredActions"));
                if (!hasOtpCredential(subject)) actions.add("CONFIGURE_TOTP");
                update.put("requiredActions", List.copyOf(actions));
            }
            updateUser(subject, update);
            addRealmRole(subject, IdentityAdminGateway.realmRole(roleCode));
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity authorization update failed", exception);
        }
    }

    @Override
    public void sendInvitation(String subject) {
        if (!properties.isEmailDeliveryEnabled()) {
            throw new DomainException("Email delivery is not configured; issue a one-time temporary password instead");
        }
        Map<String, Object> user = getUser(subject);
        LinkedHashSet<String> actions = stringSet(user.get("requiredActions"));
        if (!Boolean.TRUE.equals(user.get("emailVerified"))) actions.add("VERIFY_EMAIL");
        actions.add("UPDATE_PASSWORD");
        executeActionsEmail(subject, actions, "Invitation email could not be sent");
    }

    @Override
    public void sendRecovery(String subject, boolean resetMfa, boolean requireMfa) {
        if (!properties.isEmailDeliveryEnabled()) {
            throw new DomainException("Email delivery is not configured; issue a one-time temporary password instead");
        }
        Map<String, Object> user = getUser(subject);
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        actions.add("UPDATE_PASSWORD");
        if (!Boolean.TRUE.equals(user.get("emailVerified"))) actions.add("VERIFY_EMAIL");
        if (resetMfa) {
            deleteOtpCredentials(subject);
            if (requireMfa) actions.add("CONFIGURE_TOTP");
        }
        executeActionsEmail(subject, actions, "Account recovery email could not be sent");
        logout(subject);
    }

    private void executeActionsEmail(String subject, Collection<String> actions, String failureMessage) {
        try {
            http.put()
                    .uri(builder -> {
                        var uri = builder.path(adminPath("/users/{subject}/execute-actions-email"))
                                .queryParam("lifespan", properties.getInvitationLifespanHours() * 3600L);
                        if (!properties.getWebClientId().isBlank()) {
                            uri.queryParam("client_id", properties.getWebClientId());
                        }
                        if (!properties.getInvitationRedirectUri().isBlank()) {
                            uri.queryParam("redirect_uri", properties.getInvitationRedirectUri());
                        }
                        return uri.build(subject);
                    })
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.copyOf(actions))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw unavailable(failureMessage, exception);
        }
    }

    @Override
    public String resetTemporaryPassword(String subject, boolean requireMfa, boolean resetMfa) {
        String password = newTemporaryPassword();
        try {
            if (resetMfa) deleteOtpCredentials(subject);
            http.put()
                    .uri(adminPath("/users/{subject}/reset-password"), subject)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("type", "password", "value", password, "temporary", true))
                    .retrieve()
                    .toBodilessEntity();
            Map<String, Object> user = getUser(subject);
            LinkedHashSet<String> actions = stringSet(user.get("requiredActions"));
            actions.add("UPDATE_PASSWORD");
            actions.remove("VERIFY_EMAIL");
            if (requireMfa && (resetMfa || !hasOtpCredential(subject))) actions.add("CONFIGURE_TOTP");
            updateUser(subject, Map.of("requiredActions", List.copyOf(actions)));
            logout(subject);
            return password;
        } catch (RestClientResponseException exception) {
            throw unavailable("Temporary password reset failed", exception);
        }
    }

    @Override
    public void logout(String subject) {
        try {
            http.post()
                    .uri(adminPath("/users/{subject}/logout"), subject)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity sessions could not be revoked", exception);
        }
    }

    @Override
    public void deleteIfCreated(ProvisionedIdentity identity) {
        if (identity.created()) deleteUser(identity.subject());
    }

    @Override
    public List<LoginEvent> loginEvents(int maximum) {
        int boundedMaximum = Math.max(1, Math.min(maximum, 200));
        try {
            List<Map<String, Object>> events = http.get()
                    .uri(builder -> builder.path(adminPath("/events"))
                            .queryParam("type", "LOGIN", "LOGIN_ERROR")
                            .queryParam("max", boundedMaximum)
                            .build())
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            if (events == null) return List.of();
            return events.stream().map(this::loginEvent).toList();
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity login events could not be loaded", exception);
        }
    }

    private LoginEvent loginEvent(Map<String, Object> event) {
        long millis = number(event.get("time"));
        String type = string(event.get("type"));
        Map<String, Object> details = map(event.get("details"));
        String error = string(event.get("error"));
        if (error.isBlank()) error = string(details.get("error"));
        return new LoginEvent(Instant.ofEpochMilli(millis), type, string(event.get("userId")),
                string(details.get("username")), string(event.get("ipAddress")),
                string(event.get("clientId")), error,
                "LOGIN_ERROR".equals(type) || !error.isBlank() ? "WARNING" : "NORMAL");
    }

    private Map<String, Object> findExact(String field, String value) {
        List<Map<String, Object>> users;
        try {
            users = http.get()
                    .uri(builder -> builder.path(adminPath("/users"))
                            .queryParam(field, value).queryParam("exact", true).queryParam("max", 2).build())
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity user lookup failed", exception);
        }
        if (users == null || users.isEmpty()) return null;
        return users.getFirst();
    }

    private Map<String, Object> getUser(String subject) {
        try {
            Map<String, Object> user = http.get()
                    .uri(adminPath("/users/{subject}"), subject)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            return Objects.requireNonNull(user, "Identity user response is empty");
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity user could not be loaded", exception);
        }
    }

    private void updateUser(String subject, Map<String, Object> update) {
        http.put()
                .uri(adminPath("/users/{subject}"), subject)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(update)
                .retrieve()
                .toBodilessEntity();
    }

    private void addRealmRole(String subject, String roleName) {
        Map<String, Object> role = http.get()
                .uri(adminPath("/roles/{role}"), roleName)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        http.post()
                .uri(adminPath("/users/{subject}/role-mappings/realm"), subject)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(List.of(Objects.requireNonNull(role, "Realm role response is empty")))
                .retrieve()
                .toBodilessEntity();
    }

    private boolean hasOtpCredential(String subject) {
        return credentials(subject).stream().anyMatch(item -> "otp".equals(item.get("type")));
    }

    private void deleteOtpCredentials(String subject) {
        for (Map<String, Object> credential : credentials(subject)) {
            if (!"otp".equals(credential.get("type"))) continue;
            String credentialId = string(credential.get("id"));
            if (credentialId.isBlank()) continue;
            http.delete()
                    .uri(adminPath("/users/{subject}/credentials/{credentialId}"), subject, credentialId)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    private List<Map<String, Object>> credentials(String subject) {
        List<Map<String, Object>> credentials = http.get()
                .uri(adminPath("/users/{subject}/credentials"), subject)
                .headers(headers -> headers.setBearerAuth(accessToken()))
                .retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return credentials == null ? List.of() : credentials;
    }

    private void deleteUser(String subject) {
        try {
            http.delete()
                    .uri(adminPath("/users/{subject}"), subject)
                    .headers(headers -> headers.setBearerAuth(accessToken()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException cleanupFailure) {
            LOG.warn("Could not compensate identity user creation subject={}", subject, cleanupFailure);
        }
    }

    private String accessToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        try {
            Map<String, Object> token = http.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", properties.getRealm())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            String accessToken = token == null ? "" : string(token.get("access_token"));
            if (accessToken.isBlank()) throw new ServiceUnavailableException("Identity token response is empty");
            return accessToken;
        } catch (RestClientResponseException exception) {
            throw unavailable("Identity administration authentication failed", exception);
        }
    }

    private String adminPath(String suffix) {
        return "/admin/realms/" + properties.getRealm() + suffix;
    }

    private ServiceUnavailableException unavailable(String message, RestClientResponseException exception) {
        LOG.warn("{} status={}", message, exception.getStatusCode().value());
        return new ServiceUnavailableException(message, exception);
    }

    private static String newTemporaryPassword() {
        StringBuilder password = new StringBuilder(20);
        password.append("Aq7!");
        while (password.length() < 20) password.append(PASSWORD_CHARS[RANDOM.nextInt(PASSWORD_CHARS.length)]);
        List<Character> shuffled = new ArrayList<>();
        password.chars().mapToObj(value -> (char) value).forEach(shuffled::add);
        java.util.Collections.shuffle(shuffled, RANDOM);
        StringBuilder result = new StringBuilder(20);
        shuffled.forEach(result::append);
        return result.toString();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value.strip().replaceAll("/+$", "");
    }

    private static String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(string(value));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    private static Map<String, Object> mutableAttributes(Object value) {
        return new LinkedHashMap<>(map(value));
    }

    private static LinkedHashSet<String> stringSet(Object value) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (value instanceof Collection<?> collection) collection.stream().map(String::valueOf).forEach(values::add);
        else if (value != null) values.add(String.valueOf(value));
        return values;
    }
}
