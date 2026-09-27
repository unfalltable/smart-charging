package io.smartcharge.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.identity.admin")
public class IdentityAdminProperties {
    private String baseUri = "";
    private String realm = "";
    private String clientId = "";
    private String clientSecret = "";
    private String webClientId = "";
    private String invitationRedirectUri = "";
    private boolean emailDeliveryEnabled;
    private int invitationLifespanHours = 48;

    public String getBaseUri() { return baseUri; }
    public void setBaseUri(String baseUri) { this.baseUri = baseUri; }
    public String getRealm() { return realm; }
    public void setRealm(String realm) { this.realm = realm; }
    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getWebClientId() { return webClientId; }
    public void setWebClientId(String webClientId) { this.webClientId = webClientId; }
    public String getInvitationRedirectUri() { return invitationRedirectUri; }
    public void setInvitationRedirectUri(String invitationRedirectUri) { this.invitationRedirectUri = invitationRedirectUri; }
    public boolean isEmailDeliveryEnabled() { return emailDeliveryEnabled; }
    public void setEmailDeliveryEnabled(boolean emailDeliveryEnabled) { this.emailDeliveryEnabled = emailDeliveryEnabled; }
    public int getInvitationLifespanHours() { return invitationLifespanHours; }
    public void setInvitationLifespanHours(int invitationLifespanHours) { this.invitationLifespanHours = invitationLifespanHours; }
}
