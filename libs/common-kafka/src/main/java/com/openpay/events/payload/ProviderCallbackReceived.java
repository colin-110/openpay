package com.openpay.events.payload;

import java.util.UUID;

/**
 * A provider reported the outcome of a transaction.
 *
 * <p>{@code providerEventId} is the provider's own identifier for this callback and is what makes
 * deduplication possible: acquirers retry callbacks, so the same outcome arrives more than once.
 */
public record ProviderCallbackReceived(
        UUID paymentId,
        String providerName,
        String providerReference,
        String providerEventId,
        ProviderOutcome outcome,
        String failureReason) {

    public enum ProviderOutcome {
        AUTHORIZED,
        CAPTURED,
        FAILED
    }
}
