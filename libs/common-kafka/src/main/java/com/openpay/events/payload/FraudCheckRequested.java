package com.openpay.events.payload;

import java.util.UUID;

/**
 * A payment was submitted for screening.
 *
 * <p>Nothing in the payment flow waits on this: the gate itself is a synchronous call, and this
 * event exists so risk analytics can see the traffic that was screened rather than only the traffic
 * that was stopped. Consumers that only care about outcomes should read
 * {@code fraud.check-completed.v1} instead.
 */
public record FraudCheckRequested(
        UUID paymentId,
        UUID merchantId,
        long amount,
        String currency,
        String paymentMethodType) {
}
