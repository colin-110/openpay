package com.openpay.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.kafka.error-handling")
public class KafkaErrorHandlingProperties {

    /** Redeliveries before a record is sent to its dead-letter topic. */
    private long maxRetries = 3;

    private long retryIntervalMs = 1000;

    /**
     * Records claimed per poll. Kafka's default is 500; see
     * {@link KafkaErrorHandlingAutoConfiguration#boundedPollSize} for why that default livelocks a
     * consumer that is behind rather than merely slowing it down.
     */
    private int maxPollRecords = 50;

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

    public int getMaxPollRecords() {
        return maxPollRecords;
    }

    public void setMaxPollRecords(int maxPollRecords) {
        this.maxPollRecords = maxPollRecords;
    }
}
