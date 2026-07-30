package com.openpay.merchant.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MerchantResponse(
        UUID id,
        String merchantCode,
        String legalName,
        String status,
        String webhookUrl,
        String defaultCurrency,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
