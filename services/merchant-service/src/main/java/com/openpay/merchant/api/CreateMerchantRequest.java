package com.openpay.merchant.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateMerchantRequest(
        @NotBlank @Size(max = 50) String merchantCode,
        @NotBlank @Size(max = 255) String legalName,
        @Size(max = 512) String webhookUrl,
        @NotBlank @Pattern(regexp = "^[A-Z]{3}$") String defaultCurrency) {
}
