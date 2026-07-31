package com.openpay.payment.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateRefundRequest(
        @NotNull UUID paymentId,

        /** Minor units. Omit to refund everything still refundable on the payment. */
        @Min(value = 1, message = "must be at least 1 minor unit")
        Long amount,

        @Size(max = 255) String reason) {
}
