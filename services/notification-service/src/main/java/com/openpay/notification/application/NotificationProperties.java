package com.openpay.notification.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.notification")
public class NotificationProperties {

    private String merchantBaseUrl = "http://localhost:8082";

    /** Shared platform secret, used to read a merchant's delivery configuration. */
    private String adminToken = "";

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

    public String getMerchantBaseUrl() {
        return merchantBaseUrl;
    }

    public void setMerchantBaseUrl(String merchantBaseUrl) {
        this.merchantBaseUrl = merchantBaseUrl;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
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
