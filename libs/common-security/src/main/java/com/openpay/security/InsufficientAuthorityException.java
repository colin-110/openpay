package com.openpay.security;

/**
 * The caller is who they say they are, but is not allowed to do this.
 *
 * <p>Distinct from an authentication failure on purpose: 401 invites the client to try a different
 * credential, and a read-only key retrying with the same key forever helps nobody.
 */
public class InsufficientAuthorityException extends RuntimeException {

    private final String authority;

    public InsufficientAuthorityException(String action, String authority) {
        super("This credential is not permitted to " + action);
        this.authority = authority;
    }

    public String getAuthority() {
        return authority;
    }
}
