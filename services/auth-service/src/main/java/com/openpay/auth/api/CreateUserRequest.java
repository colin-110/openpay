package com.openpay.auth.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreateUserRequest(
        @NotNull UUID merchantId,
        @NotBlank @Email @Size(max = 255) String email,
        // Long rather than complex: length is the property that actually resists guessing, and
        // composition rules mostly push people towards predictable substitutions.
        @NotBlank @Size(min = 12, max = 200, message = "must be at least 12 characters") String password,
        @NotBlank @Pattern(regexp = "MERCHANT_ADMIN|MERCHANT_VIEWER") String role) {
}
