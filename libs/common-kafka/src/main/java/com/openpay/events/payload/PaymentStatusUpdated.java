package com.openpay.events.payload;

import java.util.UUID;

/** A payment moved between states. Downstream ledger and settlement work keys off this. */
public record PaymentStatusUpdated(
        UUID paymentId,
        UUID merchantId,
        String fromStatus,
        String toStatus,
        long amount,
        String currency) {
}
