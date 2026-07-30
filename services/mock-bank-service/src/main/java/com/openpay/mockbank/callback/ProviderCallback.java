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
        String providerName,
        String providerReference,
        String outcome,
        String failureReason,
        OffsetDateTime occurredAt) {
}
