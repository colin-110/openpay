package com.openpay.audit;

/**
 * The things worth being able to prove happened.
 *
 * <p>An enum rather than free text, so the log can be queried and alerted on. The set is
 * deliberately short: an audit log that records everything is one nobody reads, and the entries
 * that matter are the ones about credentials and identities — who was given the ability to move
 * money, by whom, and when it was taken away again.
 */
public enum AuditAction {

    /** A human signed in successfully. */
    LOGIN_SUCCEEDED,

    /**
     * A sign-in was refused. Recorded with the email that was tried, because a burst of these
     * against one address is the signal, and it is invisible if only successes are kept.
     */
    LOGIN_FAILED,

    /** Sign-in was refused without checking the password, because the caller was being throttled. */
    LOGIN_THROTTLED,

    /** An API key was issued. The key itself is never recorded — only that one now exists. */
    API_KEY_ISSUED,

    /** A dashboard user was created. */
    USER_CREATED,

    /** A merchant was onboarded. */
    MERCHANT_CREATED,

    /** A merchant's webhook signing secret was replaced. */
    WEBHOOK_SECRET_ROTATED,

    /** A session was renewed without re-entering credentials. */
    SESSION_REFRESHED,

    /**
     * A refresh token was presented after it had already been rotated away — the signature of a
     * stolen token being replayed once its rightful owner had already moved on. Every other active
     * session for the user is revoked in response, not just this one token.
     */
    REFRESH_TOKEN_REUSE_DETECTED,

    /** A session was ended deliberately. */
    LOGOUT
}
