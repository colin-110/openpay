package com.openpay.fraud.application;

import java.util.UUID;

public class DecisionNotFoundException extends RuntimeException {

    public DecisionNotFoundException(UUID paymentId) {
        super("No screening decision recorded for payment " + paymentId);
    }
}
