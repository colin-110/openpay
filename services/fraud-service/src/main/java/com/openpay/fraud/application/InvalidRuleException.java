package com.openpay.fraud.application;

public class InvalidRuleException extends RuntimeException {

    public InvalidRuleException(String message) {
        super(message);
    }
}
