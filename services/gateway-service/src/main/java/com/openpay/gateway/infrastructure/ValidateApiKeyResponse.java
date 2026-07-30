package com.openpay.gateway.infrastructure;

import java.util.UUID;

public record ValidateApiKeyResponse(
        boolean valid,
        UUID merchantId,
        String scope,
        String status) {
}
