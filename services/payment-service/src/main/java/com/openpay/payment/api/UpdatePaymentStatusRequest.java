package com.openpay.payment.api;

import com.openpay.payment.domain.PaymentStatus;
import jakarta.validation.constraints.NotNull;

public record UpdatePaymentStatusRequest(@NotNull PaymentStatus status) {
}
