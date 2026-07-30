package com.openpay.payment.domain;

public class InvalidPaymentTransitionException extends RuntimeException {

    public InvalidPaymentTransitionException(PaymentStatus from, PaymentStatus to) {
        super("Payment cannot move from " + from + " to " + to
                + (from.isTerminal() ? " because " + from + " is terminal" : ""));
    }
}
