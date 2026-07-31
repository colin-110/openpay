package com.openpay.notification.domain;

public enum DeliveryStatus {
    /** Waiting for its first attempt, or for a retry. */
    PENDING,
    /** The merchant answered 2xx. Terminal. */
    DELIVERED,
    /** Every attempt was used up. Terminal, and visible for manual replay. */
    ABANDONED
}
