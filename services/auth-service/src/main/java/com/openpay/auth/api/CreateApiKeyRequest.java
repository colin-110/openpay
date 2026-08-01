package com.openpay.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateApiKeyRequest(
        @NotNull UUID merchantId,
        @NotBlank @Size(max = 100) String name,
        /**
         * Constrained to a known vocabulary. An open-ended string was effectively decoration:
         * nothing could enforce a scope it did not recognise, so every key behaved as full access.
         */
        @NotBlank
        @Pattern(regexp = "payments:read|payments:write", message = "must be payments:read or payments:write")
        @Size(max = 255) String scope,
        OffsetDateTime expiresAt) {
}
