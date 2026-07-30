package com.openpay.payment.application;

import com.openpay.payment.api.PaymentResponse;

/**
 * Carries whether a create call actually created something, so the controller can answer 201 for a
 * new payment and 200 for an idempotent replay.
 */
public record PaymentResult(PaymentResponse payment, boolean created) {
}
