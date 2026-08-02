package com.openpay.auth.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * What the dashboard needs after a successful login or a refresh — the same shape either way, since
 * a refresh hands back a new session exactly like signing in does.
 *
 * <p>{@code refreshToken} is returned exactly once, here, the same rule as an API key. Only its
 * hash is ever stored.
 */
public record LoginResponse(
        String token,
        OffsetDateTime expiresAt,
        String refreshToken,
        OffsetDateTime refreshExpiresAt,
        UUID userId,
        UUID merchantId,
        String email,
        String role) {
}
