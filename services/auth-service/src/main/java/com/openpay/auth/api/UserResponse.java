package com.openpay.auth.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record UserResponse(
        UUID id,
        UUID merchantId,
        String email,
        String role,
        String status,
        OffsetDateTime lastLoginAt) {
}
