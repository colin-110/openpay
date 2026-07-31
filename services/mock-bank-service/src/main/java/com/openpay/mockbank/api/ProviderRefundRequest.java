package com.openpay.mockbank.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** What the router sends an acquirer to give money back. */
public record ProviderRefundRequest(
        @NotNull UUID refundId,
        @NotNull UUID paymentId,
        @NotNull @Min(1) Long amount,
        @NotBlank String currency,
        /** The reference the acquirer gave for the original payment. */
        String providerReference) {
}
