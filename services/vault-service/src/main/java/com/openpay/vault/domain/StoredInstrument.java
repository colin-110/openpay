package com.openpay.vault.domain;

import java.util.UUID;

/**
 * What a token actually refers to — and note what is missing from it.
 *
 * <p>There is no card number here, and none is held anywhere else either. A real vault at a real
 * acquirer does retain the PAN, because something downstream eventually has to present it to a card
 * network. Nothing on this platform ever does: the acquirers are simulated, and a simulated acquirer
 * asking for a real card number would be theatre.
 *
 * <p>So the PAN is validated, reduced to a network and four digits, and dropped inside the request
 * that carried it. Retaining it would mean holding the one secret worth stealing in order to satisfy
 * a reader that does not exist — which is a worse position than not holding it, not a more realistic
 * one. If a real acquirer were ever added, this is the class that would grow an encrypted PAN field,
 * and it is the only one that would.
 *
 * <p>{@code merchantId} is recorded so a token minted by one merchant's publishable key cannot be
 * redeemed against another merchant's payment. Without it, a token would be a bearer credential that
 * any merchant could spend.
 */
public record StoredInstrument(
        String type,
        String network,
        String last4,
        String vpa,
        String bank,
        UUID merchantId) {
}
