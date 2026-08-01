package com.openpay.events.payload;

import java.util.UUID;

/**
 * The final risk answer for a payment.
 *
 * <p>{@code outcome} is {@code ALLOW} or {@code BLOCK} — never {@code REVIEW}. A review is not an
 * answer, it is the absence of one, and publishing it would invite consumers to guess. The event is
 * emitted when screening decides immediately, and again — with the resolved outcome — when a human
 * closes a review.
 *
 * <p>{@code ruleName} and {@code reason} are carried so a consumer can say <em>why</em> a payment
 * failed without calling back into fraud-service for it.
 */
public record FraudCheckCompleted(
        UUID paymentId,
        UUID merchantId,
        String outcome,
        String ruleName,
        String reason,
        boolean fromReview) {
}
