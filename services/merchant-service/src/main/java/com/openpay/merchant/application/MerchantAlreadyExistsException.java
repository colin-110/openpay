package com.openpay.merchant.application;

public class MerchantAlreadyExistsException extends RuntimeException {

    public MerchantAlreadyExistsException(String message) {
        super(message);
    }
}
