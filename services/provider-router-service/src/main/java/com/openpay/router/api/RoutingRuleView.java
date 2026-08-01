package com.openpay.router.api;

import com.openpay.router.domain.RoutingRule;
import java.time.OffsetDateTime;
import java.util.UUID;

public record RoutingRuleView(
        UUID id,
        String providerName,
        String baseUrl,
        int priority,
        boolean enabled,
        /** Null means every merchant. A rule naming one replaces the general rules for them. */
        UUID merchantId,
        /** Null means every currency. */
        String currency,
        /** Half-open band in minor units: {@code [minAmount, maxAmount)}. Nulls are unbounded. */
        Long minAmount,
        Long maxAmount,
        OffsetDateTime updatedAt) {

    public static RoutingRuleView of(RoutingRule rule) {
        return new RoutingRuleView(
                rule.getId(),
                rule.getProviderName(),
                rule.getBaseUrl(),
                rule.getPriority(),
                rule.isEnabled(),
                rule.getMerchantId(),
                rule.getCurrency(),
                rule.getMinAmount(),
                rule.getMaxAmount(),
                rule.getUpdatedAt());
    }
}
