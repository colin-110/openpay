package com.openpay.router.application;

import java.util.UUID;

public class RoutingRuleNotFoundException extends RuntimeException {

    public RoutingRuleNotFoundException(UUID ruleId) {
        super("No routing rule with id " + ruleId);
    }
}
