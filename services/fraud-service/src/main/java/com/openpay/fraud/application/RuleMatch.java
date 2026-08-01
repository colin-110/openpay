package com.openpay.fraud.application;

import com.openpay.fraud.domain.FraudRule;

/** The rule that stopped a payment, and a sentence explaining what it saw. */
public record RuleMatch(FraudRule rule, String reason) {
}
