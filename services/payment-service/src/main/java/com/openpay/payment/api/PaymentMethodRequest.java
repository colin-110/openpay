package com.openpay.payment.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * How the customer is paying.
 *
 * <p>Every field is optional, including the whole object: an integration that only sends an amount
 * still works, and a payment with no method recorded says so rather than guessing.
 *
 * <p>{@code token} is the instrument reference the acquirer needs. It is accepted and never stored.
 */
public record PaymentMethodRequest(
        @Pattern(
                regexp = "^(card|upi|netbanking|wallet)$",
                message = "must be one of card, upi, netbanking, wallet")
        String type,

        @Pattern(
                regexp = "^(visa|mastercard|rupay|amex|diners)$",
                message = "must be a known card network")
        String network,

        @Pattern(regexp = "^[0-9]{4}$", message = "must be the last 4 digits")
        String last4,

        @Pattern(regexp = "^[\\w.\\-]{2,64}@[a-zA-Z]{2,64}$", message = "must be a UPI address")
        String vpa,

        @Size(max = 60) String bank,

        @Size(max = 255) String token) {
}
