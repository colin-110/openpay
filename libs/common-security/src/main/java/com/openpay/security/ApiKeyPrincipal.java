package com.openpay.security;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller behind a request, whether it presented an API key or a dashboard
 * session.
 *
 * <p>This is the only trustworthy source of merchant identity. Services must never read the
 * merchant from a client-supplied header.
 *
 * <p>{@code authority} carries whichever vocabulary the credential arrived with: an API key's scope
 * ({@code payments:write}) or a session's role ({@code MERCHANT_ADMIN}). Two vocabularies for one
 * question — may this caller move money — because the two credentials are issued by different
 * people for different reasons, and collapsing them at the point of issue would force either
 * merchants to pick roles or operators to pick scopes.
 */
public record ApiKeyPrincipal(UUID merchantId, String authority) {

    /** The only authorities that may create a payment or send money back out. Everything else reads. */
    private static final Set<String> WRITE_AUTHORITIES = Set.of("payments:write", "MERCHANT_ADMIN");

    /**
     * Deliberately an allowlist. A credential carrying an authority nobody recognises gets read
     * access, not write access: an unrecognised value is a reason to be careful, not a reason to
     * assume the best.
     */
    public boolean canWrite() {
        return authority != null && WRITE_AUTHORITIES.contains(authority);
    }

    /** Guards an action that moves money or changes state. */
    public void requireWrite(String action) {
        if (!canWrite()) {
            throw new InsufficientAuthorityException(action, authority);
        }
    }
}
