package com.openpay.fraud.application;

import java.util.UUID;

public class RuleNotFoundException extends RuntimeException {

    public RuleNotFoundException(UUID ruleId) {
        super("No fraud rule with id " + ruleId);
    }
}
