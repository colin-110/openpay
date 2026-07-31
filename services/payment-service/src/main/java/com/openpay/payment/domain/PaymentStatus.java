package com.openpay.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle.
 *
 * <p>A subset of the states in the architecture document: the ones this system actually moves
 * through today. {@code SETTLED} is deliberately absent -- settlement is tracked by
 * settlement-service against its own records, and duplicating it here would create two sources of
 * truth for whether a merchant has been paid.
 */
public enum PaymentStatus {

    /** Accepted and persisted, waiting to be routed to a provider. */
    CREATED,

    /** Handed to a provider; the outcome will arrive asynchronously by callback. */
    PENDING_PROVIDER,

    /** The provider reserved the funds. */
    AUTHORIZED,

    /** The provider captured the funds. Terminal for this phase. */
    CAPTURED,

    /** The provider declined, or routing exhausted every provider. Terminal. */
    FAILED,

    /** Withdrawn before capture. Terminal. */
    CANCELLED,

    /** Every minor unit has been returned to the customer. Terminal. */
    REFUNDED;

    // CREATED accepts AUTHORIZED and CAPTURED directly, not just PENDING_PROVIDER.
    //
    // Routing and provider callbacks arrive on separate topics, so nothing orders them relative to
    // each other. When a callback is processed before the routing notification, refusing it would
    // drop the real outcome and leave the payment stranded in PENDING_PROVIDER once the late
    // notification lands. Reaching AUTHORIZED already implies a provider answered, so accepting it
    // loses no safety: the guarantee that a merchant cannot advance its own payment lives at the
    // API, which has no transition endpoint at all.
    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PENDING_PROVIDER, AUTHORIZED, CAPTURED, FAILED, CANCELLED),
            PENDING_PROVIDER, EnumSet.of(AUTHORIZED, CAPTURED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED, CANCELLED),
            // Only a fully returned payment leaves CAPTURED. A partial refund does not move the
            // payment at all; the refunds themselves carry that detail.
            CAPTURED, EnumSet.of(REFUNDED),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class),
            REFUNDED, EnumSet.noneOf(PaymentStatus.class));

    public boolean canTransitionTo(PaymentStatus target) {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()).isEmpty();
    }

    public Set<PaymentStatus> allowedTransitions() {
        return Set.copyOf(ALLOWED_TRANSITIONS.getOrDefault(this, Set.of()));
    }
}
