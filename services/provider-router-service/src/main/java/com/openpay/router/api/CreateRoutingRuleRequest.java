package com.openpay.router.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateRoutingRuleRequest(
        @NotBlank @Size(max = 50) String providerName,
        @NotBlank @Size(max = 500) String baseUrl,
        @NotNull Integer priority,
        UUID merchantId,
        @Size(min = 3, max = 3) String currency,
        Long minAmount,
        Long maxAmount) {
}
