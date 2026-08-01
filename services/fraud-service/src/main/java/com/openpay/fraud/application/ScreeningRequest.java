package com.openpay.fraud.application;

import java.util.UUID;

/** What the rule engine is given about a payment. */
public record ScreeningRequest(
        UUID paymentId, UUID merchantId, long amount, String currency, String paymentMethodType) {
}
