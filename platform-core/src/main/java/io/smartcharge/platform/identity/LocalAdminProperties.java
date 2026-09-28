package io.smartcharge.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.identity.local")
public class LocalAdminProperties {
    private String subject = "";
    private String username = "";
    private String password = "";
    private String displayName = "Platform Administrator";
    private String email = "";

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}
