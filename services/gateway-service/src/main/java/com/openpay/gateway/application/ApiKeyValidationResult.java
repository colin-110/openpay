package com.openpay.gateway.application;

import java.util.UUID;

public record ApiKeyValidationResult(
        boolean valid,
        UUID merchantId,
        String scope,
        String status) {
}
