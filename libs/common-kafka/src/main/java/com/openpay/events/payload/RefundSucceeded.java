package com.openpay.events.payload;

import java.util.UUID;

/**
 * Money went back to the customer.
 *
 * <p>Consumed by the ledger, which reverses the original posting, and by settlement, which accrues
 * it as a negative payable so it nets against the merchant's next payout.
 */
public record RefundSucceeded(
        UUID refundId,
        UUID paymentId,
        UUID merchantId,
        long amount,
        String currency) {
}
