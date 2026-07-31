package com.openpay.settlement.domain;

public enum SettlementItemStatus {
    /** Accrued, not yet part of a batch. */
    PENDING,
    /** Included in a settlement. */
    SETTLED
}
