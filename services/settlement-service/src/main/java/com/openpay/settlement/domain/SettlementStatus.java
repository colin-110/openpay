package com.openpay.settlement.domain;

public enum SettlementStatus {
    /** Batched and ready to pay out. */
    CREATED,
    /** Payout confirmed. Terminal for this phase. */
    COMPLETED
}
