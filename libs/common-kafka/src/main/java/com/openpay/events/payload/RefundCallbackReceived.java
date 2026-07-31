package com.openpay.events.payload;

import java.util.UUID;

/**
 * A provider reported the outcome of a refund.
 *
 * <p>Separate from {@link ProviderCallbackReceived} because a refund outcome moves a refund, not a
 * payment. Overloading one payload with a nullable refund id would leave every consumer branching
 * on which kind of event it actually received.
 */
public record RefundCallbackReceived(
        UUID refundId,
        UUID paymentId,
        String providerName,
        String providerEventId,
        RefundOutcome outcome,
        String failureReason) {

    public enum RefundOutcome {
        SUCCEEDED,
        FAILED
    }
}
