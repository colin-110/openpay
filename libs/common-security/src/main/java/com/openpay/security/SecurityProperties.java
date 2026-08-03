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

    /**
     * Shared secret for platform-operator reporting and administration that does not mint a new
     * credential: reading the ledger, running or completing a settlement window, viewing delivery
     * history across merchants. Separate from {@link #adminToken}, which is reserved for actions
     * that create a business identity or a credential capable of moving money on its own —
     * onboarding a merchant, issuing an API key, rotating a webhook secret, creating a dashboard
     * user. A token embedded in a reporting dashboard or a cron job is far more likely to leak than
     * one used rarely by a human operator, so it should not be able to do those things.
     */
    private String opsToken = "";

    /** Request path prefixes that require the ops token. */
    private List<String> opsPaths = new ArrayList<>();

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

    /**
     * How long a <em>successful</em> API key validation is remembered before auth-service is asked
     * again. This is a revocation window: for up to this long, a key that has just been revoked
     * still works. Five seconds buys back a network round-trip on every single authenticated
     * request while keeping that window shorter than a human can act in. Set to zero to disable
     * caching and call auth-service every time.
     */
    private Duration apiKeyCacheTtl = Duration.ofSeconds(5);

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

    public String getOpsToken() {
        return opsToken;
    }

    public void setOpsToken(String opsToken) {
        this.opsToken = opsToken;
    }

    public List<String> getOpsPaths() {
        return opsPaths;
    }

    public void setOpsPaths(List<String> opsPaths) {
        this.opsPaths = opsPaths;
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

    public Duration getApiKeyCacheTtl() {
        return apiKeyCacheTtl;
    }

    public void setApiKeyCacheTtl(Duration apiKeyCacheTtl) {
        this.apiKeyCacheTtl = apiKeyCacheTtl;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }
}
