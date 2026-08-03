package com.openpay.fraud.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.fraud")
public class FraudProperties {

    /** How many open reviews the queue endpoint returns at most. */
    private int reviewPageSize = 100;

    public int getReviewPageSize() {
        return reviewPageSize;
    }

    public void setReviewPageSize(int reviewPageSize) {
        this.reviewPageSize = reviewPageSize;
    }

    /**
     * How long a payment stays in the Redis velocity counters. Must comfortably exceed the longest
     * window any rule uses, or a rule would be counting against a set that has already forgotten
     * the traffic it cares about. Ten minutes covers the seeded rules many times over.
     */
    private java.time.Duration velocityRetention = java.time.Duration.ofMinutes(10);

    public java.time.Duration getVelocityRetention() {
        return velocityRetention;
    }

    public void setVelocityRetention(java.time.Duration velocityRetention) {
        this.velocityRetention = velocityRetention;
    }
}
