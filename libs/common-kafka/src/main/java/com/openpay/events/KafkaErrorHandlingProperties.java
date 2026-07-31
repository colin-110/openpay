package com.openpay.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.kafka.error-handling")
public class KafkaErrorHandlingProperties {

    /** Redeliveries before a record is sent to its dead-letter topic. */
    private long maxRetries = 3;

    private long retryIntervalMs = 1000;

    public long getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(long maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryIntervalMs() {
        return retryIntervalMs;
    }

    public void setRetryIntervalMs(long retryIntervalMs) {
        this.retryIntervalMs = retryIntervalMs;
    }
}
