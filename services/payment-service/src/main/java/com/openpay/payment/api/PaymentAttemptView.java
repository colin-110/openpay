package com.openpay.payment.api;

/**
 * One attempt at getting a payment through an acquirer.
 *
 * <p>A payment can have several: the router fails over, and a merchant asking why a payment took
 * three seconds deserves to see that the first acquirer refused it rather than being told only
 * that the payment eventually worked.
 */
public record PaymentAttemptView(
        Integer attemptNo,
        String provider,
        String status,
        String providerReference,
        String failureReason) {
}
