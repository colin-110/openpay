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
         *
         * <p>{@code tokens:create} is the publishable one, and the pattern is the reason it has to
         * be named here as well as in {@code ApiKeyPrincipal}: the allowlist decides what a scope
         * may <em>do</em>, and this decides whether it can be <em>issued</em> at all. Missing from
         * here, the capability existed and no key could ever carry it.
         */
        @NotBlank
        @Pattern(
                regexp = "payments:read|payments:write|tokens:create",
                message = "must be payments:read, payments:write or tokens:create")
        @Size(max = 255) String scope,
        OffsetDateTime expiresAt) {
}
