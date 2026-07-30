package com.openpay.security;

import java.util.UUID;

/**
 * The authenticated caller behind a request, derived from a validated API key.
 *
 * <p>This is the only trustworthy source of merchant identity. Services must never read the
 * merchant from a client-supplied header.
 */
public record ApiKeyPrincipal(UUID merchantId, String scope) {
}
