package com.openpay.mockbank.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** What the router sends an acquirer. Amount is in minor units, as everywhere else. */
public record ProviderPaymentRequest(
        @NotNull UUID paymentId,
        @NotNull @Min(1) Long amount,
        @NotBlank String currency,
        String merchantReference) {
}
