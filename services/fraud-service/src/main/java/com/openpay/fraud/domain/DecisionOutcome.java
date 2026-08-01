package com.openpay.fraud.domain;

/** The answer screening gives about one payment. */
public enum DecisionOutcome {

    /** Nothing matched. The payment proceeds to routing. */
    ALLOW,

    /** Held pending a human. Not an answer — the absence of one. */
    REVIEW,

    /** Refused. */
    BLOCK;

    /** True when the payment's fate is settled and no operator needs to look at it. */
    public boolean isFinal() {
        return this != REVIEW;
    }
}
