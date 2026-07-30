package com.openpay.security;

/** The caller presented a key that is missing, malformed, unknown, inactive, or expired. */
public class InvalidApiKeyException extends RuntimeException {

    public InvalidApiKeyException(String message) {
        super(message);
    }
}
