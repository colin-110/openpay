package com.openpay.ledger.domain;

/**
 * Determines which direction increases an account.
 *
 * <p>An asset grows on the debit side, a liability on the credit side. Money we hold from an
 * acquirer is an asset; money we owe a merchant is a liability. Getting this backwards is how a
 * ledger ends up reporting balances with the wrong sign.
 */
public enum AccountType {
    ASSET(EntryDirection.DEBIT),
    LIABILITY(EntryDirection.CREDIT);

    private final EntryDirection increasingDirection;

    AccountType(EntryDirection increasingDirection) {
        this.increasingDirection = increasingDirection;
    }

    public EntryDirection increasingDirection() {
        return increasingDirection;
    }

    public long normalise(long debits, long credits) {
        return increasingDirection == EntryDirection.DEBIT ? debits - credits : credits - debits;
    }
}
