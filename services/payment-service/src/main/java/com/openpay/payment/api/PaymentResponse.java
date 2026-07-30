package com.openpay.payment.api;

import com.openpay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        PaymentStatus status,
        BigDecimal amount,
        String currency,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
