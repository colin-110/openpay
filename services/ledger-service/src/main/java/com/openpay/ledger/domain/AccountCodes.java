package com.openpay.ledger.domain;

/** The chart of accounts, small enough to state in one place while it stays small. */
public final class AccountCodes {

    /** Funds held on our side after an acquirer captured them. Platform-owned asset. */
    public static final String GATEWAY_CLEARING = "GATEWAY_CLEARING";

    /** What we owe a merchant for captured payments. Per-merchant liability. */
    public static final String MERCHANT_PAYABLE = "MERCHANT_PAYABLE";

    /** Fees the platform kept out of settled payments. Platform-owned revenue. */
    public static final String PLATFORM_REVENUE = "PLATFORM_REVENUE";

    private AccountCodes() {
    }
}
