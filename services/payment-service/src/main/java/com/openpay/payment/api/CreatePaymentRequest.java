package com.openpay.payment.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreatePaymentRequest(
        /**
         * Amount in the currency's smallest unit: 10000 means USD 100.00, and JPY 100 means 100 yen
         * because the yen has no minor unit. Integers remove any question of rounding or scale.
         */
        @NotNull
        @Min(value = 1, message = "must be at least 1 minor unit")
        @Max(value = 99_999_999_999_999L, message = "exceeds the maximum supported amount")
        Long amount,

        @NotBlank
        @Pattern(regexp = "^[A-Z]{3}$", message = "must be a 3-letter uppercase ISO 4217 code")
        String currency) {
}
