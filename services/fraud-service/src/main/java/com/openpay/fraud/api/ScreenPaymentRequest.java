package com.openpay.fraud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What payment-service sends to have a payment screened.
 *
 * <p>The instrument is described by type only — {@code CARD}, {@code UPI} — and never by token, PAN
 * fragment, or VPA. Screening does not need them, and a risk service accumulating a second copy of
 * payment instruments is a breach waiting for a reason.
 */
public record ScreenPaymentRequest(
        @NotNull UUID paymentId,
        @NotNull UUID merchantId,
        @NotNull @Positive Long amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 20) String paymentMethodType) {
}
