package com.openpay.vault.api;

import java.time.OffsetDateTime;

/**
 * The same shape every other service on the platform returns, plus the field that failed.
 *
 * <p>{@code field} exists because this is a form: a checkout has four inputs and telling the page
 * which one to highlight is the difference between "your card was refused" and a red outline around
 * the expiry box. It names the field only — never what was typed into it.
 */
public record ErrorResponse(
        String code,
        String message,
        String field,
        String path,
        OffsetDateTime timestamp) {
}
