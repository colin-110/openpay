package com.openpay.router.application;

public class InvalidRoutingRuleException extends RuntimeException {

    public InvalidRoutingRuleException(String message) {
        super(message);
    }
}
