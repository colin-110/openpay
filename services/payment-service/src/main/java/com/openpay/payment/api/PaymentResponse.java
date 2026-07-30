package com.openpay.payment.api;

import com.openpay.payment.domain.PaymentStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        /** Amount in the currency's smallest unit. */
        Long amount,
        String currency,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
