package com.openpay.security;

/**
 * The auth service could not be reached or failed to answer.
 *
 * <p>Deliberately distinct from {@link InvalidApiKeyException}: an auth outage must surface as 503,
 * not as "your key is invalid", or a provider outage looks like a merchant integration bug.
 */
public class AuthServiceUnavailableException extends RuntimeException {

    public AuthServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
