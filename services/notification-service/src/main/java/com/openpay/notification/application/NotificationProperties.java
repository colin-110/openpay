package com.openpay.notification.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.notification")
public class NotificationProperties {

    private String merchantBaseUrl = "http://localhost:8082";

    /**
     * Service-to-service secret, used to read a merchant's delivery configuration. Not the
     * platform admin token: this service makes outbound calls to merchant-controlled URLs, so it
     * should hold as little platform-wide authority as possible.
     */
    private String internalToken = "";

    /**
     * Attempts before a delivery is abandoned. Merchants' endpoints go down for hours, so this is
     * generous; combined with the backoff below it spans roughly a day.
     */
    private int maxAttempts = 8;

    /** First retry delay. Each subsequent attempt doubles it, capped by maxBackoff. */
    private Duration initialBackoff = Duration.ofSeconds(5);

    private Duration maxBackoff = Duration.ofHours(6);

    private Duration connectTimeout = Duration.ofSeconds(3);

    /** Merchants' endpoints are outside our control, so this must be short and enforced. */
    private Duration readTimeout = Duration.ofSeconds(5);

    private int batchSize = 50;

    /** Where an abandoned delivery gets reported. Empty disables the alert. */
    private String opsEmail = "";

    public String getOpsEmail() {
        return opsEmail;
    }

    public void setOpsEmail(String opsEmail) {
        this.opsEmail = opsEmail;
    }

    public String getMerchantBaseUrl() {
        return merchantBaseUrl;
    }

    public void setMerchantBaseUrl(String merchantBaseUrl) {
        this.merchantBaseUrl = merchantBaseUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    /**
     * Whether a merchant endpoint on loopback may be delivered to. False everywhere except local
     * development, where the only reachable test endpoint is on this machine.
     */
    private boolean allowLoopbackTargets = false;

    public boolean isAllowLoopbackTargets() {
        return allowLoopbackTargets;
    }

    public void setAllowLoopbackTargets(boolean allowLoopbackTargets) {
        this.allowLoopbackTargets = allowLoopbackTargets;
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

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
