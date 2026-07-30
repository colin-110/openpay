package com.openpay.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotNull
        @DecimalMin(value = "0.01", inclusive = true)
        BigDecimal amount,

        @NotBlank
        @Size(min = 3, max = 3)
        String currency) {
}
