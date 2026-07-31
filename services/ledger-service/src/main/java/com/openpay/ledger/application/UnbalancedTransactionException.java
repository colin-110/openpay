package com.openpay.ledger.application;

public class UnbalancedTransactionException extends RuntimeException {

    public UnbalancedTransactionException(long debits, long credits) {
        super("Refusing to post an unbalanced transaction: debits " + debits + " != credits " + credits);
    }

    public UnbalancedTransactionException(String message) {
        super(message);
    }
}
