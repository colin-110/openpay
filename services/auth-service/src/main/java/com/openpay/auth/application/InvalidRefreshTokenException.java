package com.openpay.auth.application;

/**
 * A refresh token that cannot be used to mint a new session — unknown, expired, or already
 * revoked. Deliberately one message for all three: telling a caller *which* of them is true would
 * let them distinguish "this token never existed" from "this token existed and was used up",
 * information an attacker probing tokens should not get for free.
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Refresh token is invalid, expired, or has already been used");
    }
}
