package com.openpay.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CreateApiKeyRequest(
        @NotNull UUID merchantId,
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 255) String scope,
        OffsetDateTime expiresAt) {
}
