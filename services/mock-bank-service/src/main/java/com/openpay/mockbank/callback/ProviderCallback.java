package com.openpay.mockbank.callback;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The body an acquirer POSTs back when it knows the outcome.
 *
 * <p>{@code eventId} is the acquirer's identifier for this notification. Real acquirers re-send
 * callbacks until acknowledged, so the receiver needs it to tell a retry from a new event.
 */
public record ProviderCallback(
        String eventId,
        UUID paymentId,
        /** Set only when this callback concerns a refund; null for a payment outcome. */
        UUID refundId,
        String providerName,
        String providerReference,
        String outcome,
        String failureReason,
        OffsetDateTime occurredAt) {
}
