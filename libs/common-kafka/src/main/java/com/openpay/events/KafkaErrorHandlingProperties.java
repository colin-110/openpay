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

    /**
     * Consumer threads per listener, per instance.
     *
     * <p>Partitions are what allow parallelism; this is what uses it. One instance with concurrency
     * 3 against a 6-partition topic takes three partitions' worth of work in parallel, and a second
     * instance takes the rest — so the group scales both by replica and within a replica.
     *
     * <p>Three rather than six: a single instance running six consumer threads would leave nothing
     * for a second replica to pick up, which turns scaling out into a rebalance that changes
     * nothing. Concurrency beyond the partition count is simply idle threads.
     */
    private int listenerConcurrency = 3;

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

    public int getListenerConcurrency() {
        return listenerConcurrency;
    }

    public void setListenerConcurrency(int listenerConcurrency) {
        this.listenerConcurrency = listenerConcurrency;
    }
}
