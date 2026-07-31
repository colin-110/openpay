package com.openpay.events.payload;

import java.util.UUID;

/** A refund was accepted and needs sending to the provider that took the original payment. */
public record RefundCreated(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        long amount,
        String currency) {
}
