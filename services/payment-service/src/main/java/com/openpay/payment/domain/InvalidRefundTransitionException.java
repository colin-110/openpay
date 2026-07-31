package com.openpay.payment.domain;

public class InvalidRefundTransitionException extends RuntimeException {

    public InvalidRefundTransitionException(RefundStatus from, RefundStatus to) {
        super("Refund cannot move from " + from + " to " + to);
    }
}
