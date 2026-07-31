package com.openpay.payment.api;

import com.openpay.payment.domain.RefundStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RefundResponse(
        UUID id,
        UUID paymentId,
        RefundStatus status,
        Long amount,
        String currency,
        String reason,
        String failureReason,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
