package com.openpay.payment.infrastructure;

/** The router could not be reached. The payment is fine; only its attempt history is missing. */
public class AttemptsUnavailableException extends RuntimeException {

    public AttemptsUnavailableException(Throwable cause) {
        super("Routing attempts are temporarily unavailable", cause);
    }
}
