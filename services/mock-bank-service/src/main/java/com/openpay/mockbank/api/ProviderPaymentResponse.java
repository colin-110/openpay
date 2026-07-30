package com.openpay.mockbank.api;

/**
 * Acknowledgement only. A real acquirer accepts the request and reports the outcome later over a
 * callback, so the router must never treat this as an authorisation.
 */
public record ProviderPaymentResponse(
        String providerName,
        String providerReference,
        String status) {
}
