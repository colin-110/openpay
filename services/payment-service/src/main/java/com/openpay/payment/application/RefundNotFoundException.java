package com.openpay.payment.application;

import java.util.UUID;

public class RefundNotFoundException extends RuntimeException {

    public RefundNotFoundException(UUID refundId) {
        super("Refund not found: " + refundId);
    }
}
