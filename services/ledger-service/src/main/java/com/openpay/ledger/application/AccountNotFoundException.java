package com.openpay.ledger.application;

import java.util.UUID;

public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(String accountCode, UUID merchantId, String currency) {
        super("No ledger account " + accountCode + " for merchant " + merchantId + " in " + currency
                + ". Accounts are created on first posting, so this usually means nothing has "
                + "been posted for it yet.");
    }
}
