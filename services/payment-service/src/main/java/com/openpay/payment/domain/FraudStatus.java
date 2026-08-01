package com.openpay.payment.domain;

/**
 * Where a payment stands with risk screening.
 *
 * <p>Orthogonal to {@link PaymentStatus}. A held payment is still {@code CREATED} — it has simply
 * not been announced for routing yet — and folding the two together would mean unwinding a HELD
 * status back into whatever it interrupted.
 */
public enum FraudStatus {

    /** Screened and cleared, or screened before the gate existed. Routing has been announced. */
    ALLOWED,

    /** Held pending a human. Nothing has been published, so no acquirer has seen it. */
    HELD,

    /** Refused by a rule, or by an operator closing the review. The payment is FAILED. */
    BLOCKED,

    /**
     * Screening could not be reached and the deployment chose to let the payment through. Recorded
     * distinctly from ALLOWED because "we decided this was fine" and "nobody looked" are different
     * claims, and only one of them should show up in a chargeback dispute.
     */
    UNSCREENED
}
