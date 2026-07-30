package com.openpay.events.payload;

import java.util.UUID;

/** A payment was handed to a provider on a given attempt. */
public record ProviderDispatched(
        UUID paymentId,
        UUID merchantId,
        String providerName,
        String providerReference,
        int attemptNo) {
}
