package com.openpay.auth.application;

public class MerchantLookupUnavailableException extends RuntimeException {

    public MerchantLookupUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
