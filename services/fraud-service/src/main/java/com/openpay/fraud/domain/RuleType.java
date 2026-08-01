package com.openpay.fraud.domain;

/**
 * What a rule actually looks at.
 *
 * <p>Three, not thirty. Each one is something this service can evaluate from its own tables in a
 * single indexed query, which is the constraint that keeps the gate fast enough to sit inside
 * payment creation. A rule needing data from somewhere else belongs in an asynchronous consumer,
 * not here.
 */
public enum RuleType {

    /** The payment's amount exceeds the threshold, in minor units. */
    AMOUNT_OVER,

    /** The merchant has screened more than {@code threshold} payments in the last window. */
    VELOCITY_COUNT,

    /**
     * The merchant has screened more than {@code threshold} payments <em>of this exact amount</em>
     * in the last window. Card testing looks like this: the instrument changes, the amount does not.
     */
    REPEATED_AMOUNT
}
