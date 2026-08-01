package com.openpay.fraud.application;

import com.openpay.fraud.domain.DecisionOutcome;
import java.util.UUID;

/**
 * A review that cannot be resolved: it was never a review, or somebody already closed it.
 *
 * <p>Its own type rather than a bare {@code IllegalStateException}, so the API can answer 409
 * without also turning every genuine internal invariant failure into one. Mapping
 * {@code IllegalStateException} wholesale would have told a caller "somebody got there first" when
 * what actually happened was a bug.
 */
public class ReviewNotOpenException extends RuntimeException {

    public ReviewNotOpenException(UUID paymentId, DecisionOutcome outcome, DecisionOutcome resolvedOutcome) {
        super(resolvedOutcome != null
                ? "Review for payment " + paymentId + " was already resolved as " + resolvedOutcome
                : "Decision for payment " + paymentId + " is " + outcome + ", not a review");
    }
}
