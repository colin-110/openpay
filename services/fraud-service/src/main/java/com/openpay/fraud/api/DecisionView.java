package com.openpay.fraud.api;

import com.openpay.fraud.domain.DecisionOutcome;
import com.openpay.fraud.domain.FraudDecision;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A decision as an operator working the queue sees it. */
public record DecisionView(
        UUID paymentId,
        UUID merchantId,
        long amount,
        String currency,
        String paymentMethodType,
        DecisionOutcome outcome,
        DecisionOutcome resolvedOutcome,
        String resolvedBy,
        OffsetDateTime resolvedAt,
        String ruleName,
        String reason,
        OffsetDateTime createdAt) {

    public static DecisionView of(FraudDecision decision) {
        return new DecisionView(
                decision.getPaymentId(),
                decision.getMerchantId(),
                decision.getAmount(),
                decision.getCurrency(),
                decision.getPaymentMethodType(),
                decision.getOutcome(),
                decision.getResolvedOutcome(),
                decision.getResolvedBy(),
                decision.getResolvedAt(),
                decision.getRuleName(),
                decision.getReason(),
                decision.getCreatedAt());
    }
}
