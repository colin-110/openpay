package com.openpay.auth.api;

import jakarta.validation.constraints.NotBlank;

public record ValidateApiKeyRequest(@NotBlank String apiKey) {
}
