package com.openpay.payment.api;

/** The safe half of a payment method: enough to recognise it, never enough to reuse it. */
public record PaymentMethodView(
        String type,
        String network,
        String last4,
        String vpa,
        String bank) {
}
