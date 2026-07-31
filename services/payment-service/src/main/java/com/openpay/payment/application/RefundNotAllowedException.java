package com.openpay.payment.application;

/** The refund cannot be accepted: wrong payment state, or more than is left to refund. */
public class RefundNotAllowedException extends RuntimeException {

    public RefundNotAllowedException(String message) {
        super(message);
    }
}
