package com.openpay.auth.api;

import java.util.UUID;

public record ValidateApiKeyResponse(
        boolean valid,
        UUID merchantId,
        String scope,
        String status) {
}
