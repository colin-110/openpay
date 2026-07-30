package com.openpay.payment.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;

public record CreatePaymentRequest(
        @NotNull
        @DecimalMin(value = "0.01", inclusive = true)
        @DecimalMax(value = "999999999999999.9999", inclusive = true)
        // Matches NUMERIC(19,4): without this the DB would silently round a 5-decimal amount.
        @Digits(integer = 15, fraction = 4)
        BigDecimal amount,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency) {
}
