package com.openpay.payment.application;

public class IdempotencyKeyConflictException extends RuntimeException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey
                + "' was already used with a different request body. Use a new key for a new payment.");
    }
}
