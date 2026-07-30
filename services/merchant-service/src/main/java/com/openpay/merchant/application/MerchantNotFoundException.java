package com.openpay.merchant.application;

public class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException(String message) {
        super(message);
    }
}
