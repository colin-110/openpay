package com.openpay.security;

/** The URL is well formed but the platform will not make a request to it. */
public class UndeliverableUrlException extends RuntimeException {

    public UndeliverableUrlException(String problem) {
        super("webhookUrl " + problem);
    }
}
