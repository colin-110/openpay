package com.openpay.fraud.api;

import com.openpay.fraud.domain.RuleAction;
import com.openpay.fraud.domain.RuleType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * {@code windowSeconds} and {@code currency} are nullable here and checked in the service rather
 * than by annotations, because whether either is required depends on {@code ruleType}. Bean
 * validation cannot express that without a custom validator, and the error message the service
 * gives is the more useful one.
 */
public record CreateRuleRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull RuleType ruleType,
        @NotNull @Positive Long threshold,
        Integer windowSeconds,
        @Size(min = 3, max = 3) String currency,
        @NotNull RuleAction action,
        @NotNull Integer priority) {
}
