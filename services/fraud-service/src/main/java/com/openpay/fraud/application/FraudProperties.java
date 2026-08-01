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
}
