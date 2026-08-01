package com.openpay.fraud.api;

import com.openpay.fraud.domain.FraudRule;
import com.openpay.fraud.domain.RuleAction;
import com.openpay.fraud.domain.RuleType;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RuleView(
        UUID id,
        String name,
        RuleType ruleType,
        long threshold,
        Integer windowSeconds,
        String currency,
        RuleAction action,
        int priority,
        boolean enabled,
        OffsetDateTime updatedAt) {

    public static RuleView of(FraudRule rule) {
        return new RuleView(
                rule.getId(),
                rule.getName(),
                rule.getRuleType(),
                rule.getThreshold(),
                rule.getWindowSeconds(),
                rule.getCurrency(),
                rule.getAction(),
                rule.getPriority(),
                rule.isEnabled(),
                rule.getUpdatedAt());
    }
}
