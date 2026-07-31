package com.openpay.settlement.domain;

public enum SettlementItemType {
    /** A captured payment: money owed to the merchant. Positive. */
    CAPTURE,
    /** A refund the merchant gave back: money owed to us. Negative. */
    REFUND
}
