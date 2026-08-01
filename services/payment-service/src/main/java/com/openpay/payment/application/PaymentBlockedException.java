package com.openpay.payment.application;

/**
 * A payment refused by risk screening.
 *
 * <p>The message names the rule but not its threshold. A merchant told "blocked because amounts
 * over 500,000 are refused" has been handed the number to stay under, and a merchant integration
 * is not always the party being protected against.
 */
public class PaymentBlockedException extends RuntimeException {

    private final String ruleName;

    public PaymentBlockedException(String ruleName) {
        super("This payment was refused by risk screening"
                + (ruleName == null ? "" : " (" + ruleName + ")"));
        this.ruleName = ruleName;
    }

    public String getRuleName() {
        return ruleName;
    }
}
