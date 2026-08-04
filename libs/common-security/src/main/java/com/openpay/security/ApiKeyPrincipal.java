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

    /**
     * The scope carried by a <em>publishable</em> key — the one credential here that is meant to be
     * read by strangers.
     *
     * <p>Every other credential on this platform is a secret. This one is embedded in a checkout
     * page, so it is visible to anyone who opens the developer tools, and the security model cannot
     * be "nobody will look". It is instead that the key can do exactly one thing: turn a card number
     * into a single-use token. It cannot take a payment, cannot refund one, and — the part that is
     * easy to get wrong — cannot <em>read</em> one either.
     *
     * <p>That last one is why {@link #requireRead} exists. Merchant scoping alone is not enough for
     * this credential: it would confine a page-visible key to one merchant's payments rather than to
     * none of them, and "only every order this shop has ever taken" is not a limit worth having.
     */
    public static final String PUBLISHABLE_SCOPE = "tokens:create";

    /** The only authorities that may create a payment or send money back out. Everything else reads. */
    private static final Set<String> WRITE_AUTHORITIES = Set.of("payments:write", "MERCHANT_ADMIN");

    /**
     * Who may read a merchant's payments.
     *
     * <p>An allowlist, like the one above and for the same reason. It is spelled out rather than
     * expressed as "anyone who is not publishable", because a denylist would silently readmit the
     * next page-visible credential somebody adds — and the whole vocabulary is four values, so
     * there is nothing to be gained by being clever about it.
     */
    private static final Set<String> READ_AUTHORITIES =
            Set.of("payments:write", "payments:read", "MERCHANT_ADMIN", "MERCHANT_VIEWER");

    /**
     * Who may exchange an instrument for a token. A publishable key, obviously — and a secret key
     * too, so that a server-side integration is not forced to hold a second credential just to do
     * the one thing its existing one already outranks.
     */
    private static final Set<String> TOKENIZE_AUTHORITIES =
            Set.of(PUBLISHABLE_SCOPE, "payments:write", "MERCHANT_ADMIN");

    /**
     * Deliberately an allowlist. A credential carrying an authority nobody recognises gets read
     * access, not write access: an unrecognised value is a reason to be careful, not a reason to
     * assume the best.
     */
    public boolean canWrite() {
        return authority != null && WRITE_AUTHORITIES.contains(authority);
    }

    public boolean canRead() {
        return authority != null && READ_AUTHORITIES.contains(authority);
    }

    public boolean canTokenize() {
        return authority != null && TOKENIZE_AUTHORITIES.contains(authority);
    }

    /** True for a credential meant to be visible in a page, which is allowed to do almost nothing. */
    public boolean isPublishable() {
        return PUBLISHABLE_SCOPE.equals(authority);
    }

    /** Guards an action that moves money or changes state. */
    public void requireWrite(String action) {
        if (!canWrite()) {
            throw new InsufficientAuthorityException(action, authority);
        }
    }

    /** Guards reading a merchant's own data. */
    public void requireRead(String action) {
        if (!canRead()) {
            throw new InsufficientAuthorityException(action, authority);
        }
    }

    /** Guards turning a real instrument into a token. */
    public void requireTokenize(String action) {
        if (!canTokenize()) {
            throw new InsufficientAuthorityException(action, authority);
        }
    }
}
