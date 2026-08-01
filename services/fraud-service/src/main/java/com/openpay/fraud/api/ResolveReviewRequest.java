package com.openpay.fraud.api;

import com.openpay.fraud.domain.DecisionOutcome;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code resolvedBy} is required and free text. It is who to ask about this decision six months
 * from now, and a review queue where every entry says "operator" is a review queue with no
 * accountability.
 */
public record ResolveReviewRequest(
        @NotNull DecisionOutcome outcome,
        @NotBlank @Size(max = 100) String resolvedBy) {
}
