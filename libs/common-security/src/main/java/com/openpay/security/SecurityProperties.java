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

    /**
     * Shared secret for service-to-service calls, presented as {@code X-Internal-Token}. Separate
     * from the admin token so a service that reads one thing from a peer does not have to hold the
     * credential that opens merchant onboarding, key issuance, and the ledger.
     */
    private String internalToken = "";

    /** Request path prefixes that require the internal service token. */
    private List<String> internalPaths = new ArrayList<>();

    /** Shared HS256 key for verifying dashboard sessions. Blank disables session auth. */
    private String jwtSecret = "";

    /**
     * Browser origins allowed to call this service. Empty by default, and deliberately so: only the
     * two services a dashboard talks to directly should answer cross-origin requests. Setting it on
     * an internal service would let a page reach past the gateway.
     */
    private List<String> allowedOrigins = new ArrayList<>();

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

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public List<String> getInternalPaths() {
        return internalPaths;
    }

    public void setInternalPaths(List<String> internalPaths) {
        this.internalPaths = internalPaths;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
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
