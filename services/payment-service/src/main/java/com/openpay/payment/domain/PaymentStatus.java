package com.openpay.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Payment lifecycle.
 *
 * <p>A subset of the states in the architecture document: the ones this system actually moves
 * through today. {@code REFUND_PENDING}, {@code REFUNDED}, and {@code SETTLED} arrive with the
 * refund and settlement phases, and adding them before anything can produce them would be
 * decoration rather than a state machine.
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
    CANCELLED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            CREATED, EnumSet.of(PENDING_PROVIDER, FAILED, CANCELLED),
            PENDING_PROVIDER, EnumSet.of(AUTHORIZED, CAPTURED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED, CANCELLED),
            CAPTURED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class),
            CANCELLED, EnumSet.noneOf(PaymentStatus.class));

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
