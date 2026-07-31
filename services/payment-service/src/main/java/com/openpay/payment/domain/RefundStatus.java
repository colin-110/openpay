package com.openpay.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Refund lifecycle.
 *
 * <p>Separate from {@link PaymentStatus} because a payment can be refunded in parts: the payment
 * stays CAPTURED while individual refunds succeed or fail on their own.
 */
public enum RefundStatus {

    /** Accepted and sent to the provider; the outcome arrives by callback. */
    PENDING,

    /** The provider returned the money. Terminal. */
    SUCCEEDED,

    /** The provider refused. Terminal, and the amount is released back to the refundable balance. */
    FAILED;

    private static final Map<RefundStatus, Set<RefundStatus>> ALLOWED = Map.of(
            PENDING, EnumSet.of(SUCCEEDED, FAILED),
            SUCCEEDED, EnumSet.noneOf(RefundStatus.class),
            FAILED, EnumSet.noneOf(RefundStatus.class));

    public boolean canTransitionTo(RefundStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    public boolean isTerminal() {
        return ALLOWED.getOrDefault(this, Set.of()).isEmpty();
    }
}
