package com.openpay.vault.api;

import java.time.OffsetDateTime;

/**
 * What comes back to the browser: a token, and just enough to render a payment method.
 *
 * <p>Everything here is safe to show a customer and safe to put in a page, which is the test each
 * field had to pass to be included. A last four identifies a card to the person who owns it and to
 * nobody else; a network is public; a masked VPA names a bank rather than a person.
 */
public record TokenResponse(
        String token,
        String type,
        String network,
        String last4,
        String vpa,
        OffsetDateTime expiresAt) {
}
