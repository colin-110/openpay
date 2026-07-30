package com.openpay.security;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.security")
public class SecurityProperties {

    /** Base URL of auth-service, used to validate API keys. */
    private String authBaseUrl = "http://localhost:8081";

    /** Request path prefixes that require a valid merchant API key. */
    private List<String> apiKeyPaths = new ArrayList<>();

    /** Request path prefixes that require the platform admin token. */
    private List<String> adminPaths = new ArrayList<>();

    /**
     * Shared platform admin secret. Intentionally empty by default: an out-of-the-box default would
     * be a publicly known credential. When blank, every admin path is refused.
     */
    private String adminToken = "";

    private Duration connectTimeout = Duration.ofSeconds(2);

    private Duration readTimeout = Duration.ofSeconds(3);

    public String getAuthBaseUrl() {
        return authBaseUrl;
    }

    public void setAuthBaseUrl(String authBaseUrl) {
        this.authBaseUrl = authBaseUrl;
    }

    public List<String> getApiKeyPaths() {
        return apiKeyPaths;
    }

    public void setApiKeyPaths(List<String> apiKeyPaths) {
        this.apiKeyPaths = apiKeyPaths;
    }

    public List<String> getAdminPaths() {
        return adminPaths;
    }

    public void setAdminPaths(List<String> adminPaths) {
        this.adminPaths = adminPaths;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
