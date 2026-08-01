package com.openpay.fraud.domain;

/**
 * What happens when a rule matches.
 *
 * <p>There is no {@code ALLOW} action, deliberately. Allowing is what happens when nothing matches,
 * so an allow rule would be a way to shadow every rule below it — a footgun disguised as a feature.
 */
public enum RuleAction {

    /** Hold the payment. Nothing is routed to an acquirer until a human closes the review. */
    REVIEW,

    /** Refuse the payment outright. */
    BLOCK
}
