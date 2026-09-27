package io.smartcharge.platform.identity;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

interface IdentityAdminGateway {
    Capabilities capabilities();

    ProvisionedIdentity provision(ProvisionIdentity request);

    void addTenantAuthorization(String subject, UUID tenantId, String roleCode, boolean requireMfa);

    void sendInvitation(String subject);

    void sendRecovery(String subject, boolean resetMfa, boolean requireMfa);

    String resetTemporaryPassword(String subject, boolean requireMfa, boolean resetMfa);

    void logout(String subject);

    void deleteIfCreated(ProvisionedIdentity identity);

    List<LoginEvent> loginEvents(int maximum);

    record Capabilities(boolean managedLifecycle, boolean emailDelivery,
                        boolean temporaryPasswordFallback, int invitationLifespanHours) { }

    record ProvisionIdentity(String username, String email, String displayName, UUID tenantId,
                             String roleCode, boolean requireMfa) { }

    record ProvisionedIdentity(String subject, String username, String email, boolean created,
                               String temporaryPassword) { }

    record LoginEvent(Instant occurredAt, String type, String subject, String username,
                      String sourceIp, String clientId, String error, String risk) { }

    static String realmRole(String tenantRole) {
        return switch (tenantRole) {
            case "TENANT_ADMIN" -> "admin";
            case "OPERATOR" -> "operator";
            case "FINANCE" -> "finance";
            case "AUDITOR" -> "auditor";
            case "SUPPORT" -> "support";
            default -> throw new IllegalArgumentException("Unsupported tenant role");
        };
    }

    static Set<String> requiredActions(boolean requireMfa) {
        return requireMfa ? Set.of("UPDATE_PASSWORD", "CONFIGURE_TOTP", "VERIFY_EMAIL")
                : Set.of("UPDATE_PASSWORD", "VERIFY_EMAIL");
    }
}
