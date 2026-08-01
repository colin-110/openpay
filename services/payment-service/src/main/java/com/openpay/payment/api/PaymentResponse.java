package com.openpay.payment.api;

import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.PaymentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        /** Amount in the currency's smallest unit. */
        Long amount,
        String currency,
        /** Null when the payment was created without one. */
        PaymentMethodView paymentMethod,
        /**
         * Where the payment stands with risk screening. {@code HELD} is the one that matters to an
         * integration: the payment was accepted, and nothing will reach an acquirer until a human
         * clears it.
         */
        FraudStatus fraudStatus,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
