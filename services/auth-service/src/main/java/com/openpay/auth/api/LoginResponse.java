package com.openpay.auth.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** What the dashboard needs after a successful login. The token carries the same claims. */
public record LoginResponse(
        String token,
        OffsetDateTime expiresAt,
        UUID userId,
        UUID merchantId,
        String email,
        String role) {
}
