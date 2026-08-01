package com.openpay.fraud.api;

import com.openpay.fraud.domain.DecisionOutcome;
import com.openpay.fraud.domain.FraudDecision;

/**
 * The answer, as the caller needs it.
 *
 * <p>{@code outcome} is the effective one, so a payment re-screened after its review was closed
 * gets the operator's answer rather than the original {@code REVIEW}.
 */
public record ScreenPaymentResponse(DecisionOutcome outcome, String ruleName, String reason) {

    public static ScreenPaymentResponse of(FraudDecision decision) {
        return new ScreenPaymentResponse(
                decision.effectiveOutcome(), decision.getRuleName(), decision.getReason());
    }
}
