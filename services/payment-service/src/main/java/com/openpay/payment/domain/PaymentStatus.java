package com.openpay.payment.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum PaymentStatus {
    CREATED,
    AUTHORIZED,
    CAPTURED,
    FAILED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> ALLOWED_TRANSITIONS = Map.of(
            CREATED, EnumSet.of(AUTHORIZED, FAILED),
            AUTHORIZED, EnumSet.of(CAPTURED, FAILED),
            CAPTURED, EnumSet.noneOf(PaymentStatus.class),
            FAILED, EnumSet.noneOf(PaymentStatus.class));

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
