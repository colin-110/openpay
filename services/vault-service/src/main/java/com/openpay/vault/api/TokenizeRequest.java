package com.openpay.vault.api;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A real instrument, on its way to becoming a token.
 *
 * <p>This is the only request object on the platform that carries a card number, and it is the only
 * one that is never persisted, never logged and never echoed back. The bean validation here is
 * deliberately shallow — shape only — because the real checks (Luhn, network, expiry, security code
 * length) depend on each other and are done in one place in {@code TokenizationService}, where a
 * failure can name the field without quoting the value.
 *
 * <p>{@code number} and {@code securityCode} are {@code String}, not {@code long}: a card number has
 * meaningful leading zeros in some ranges, exceeds a 64-bit integer at 19 digits, and is an
 * identifier rather than a quantity. Nothing about it is arithmetic.
 */
public record TokenizeRequest(

        @Pattern(regexp = "^(card|upi)$", message = "must be card or upi")
        String type,

        // Separators are common in real input and stripping them is kinder than refusing them.
        @Size(max = 32) String number,

        Integer expMonth,

        Integer expYear,

        @Size(max = 4) String securityCode,

        @Size(max = 120) String vpa) {
}
