package com.openpay.auth.application;

public class InvalidApiKeyRequestException extends RuntimeException {

    public InvalidApiKeyRequestException(String message) {
        super(message);
    }
}
