package com.openpay.auth.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateApiKeyResponse(
        UUID id,
        UUID merchantId,
        String name,
        String scope,
        String keyPrefix,
        String apiKey,
        String status,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {
}
