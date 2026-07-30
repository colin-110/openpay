package com.openpay.events.payload;

import java.util.UUID;

/**
 * A payment was accepted and now needs a provider.
 *
 * @param amount in the currency's minor units
 */
public record PaymentCreated(
        UUID paymentId,
        UUID merchantId,
        long amount,
        String currency) {
}
