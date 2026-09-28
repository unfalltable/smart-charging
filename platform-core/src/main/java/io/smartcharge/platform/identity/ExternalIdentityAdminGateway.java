package io.smartcharge.platform.identity;

import java.util.List;
import java.util.UUID;
import io.smartcharge.platform.shared.domain.ServiceUnavailableException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "charging.security", name = "identity-provider-mode", havingValue = "external", matchIfMissing = true)
final class ExternalIdentityAdminGateway implements IdentityAdminGateway {
    private static final String MESSAGE = "Account lifecycle is managed by the configured external identity provider";

    @Override
    public Capabilities capabilities() {
        return new Capabilities(false, false, false, false, 0);
    }

    @Override
    public ProvisionedIdentity provision(ProvisionIdentity request) {
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public void addTenantAuthorization(String subject, UUID tenantId, String roleCode, boolean requireMfa) {
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public void sendInvitation(String subject) {
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public void sendRecovery(String subject, boolean resetMfa, boolean requireMfa) {
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public String resetTemporaryPassword(String subject, boolean requireMfa, boolean resetMfa) {
        throw new ServiceUnavailableException(MESSAGE);
    }

    @Override
    public void logout(String subject) { }

    @Override
    public void deleteIfCreated(ProvisionedIdentity identity) { }

    @Override
    public List<LoginEvent> loginEvents(int maximum) {
        return List.of();
    }
}
