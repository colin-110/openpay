package com.openpay.ledger.application;

import com.openpay.ledger.domain.AccountType;
import java.util.UUID;

/**
 * @param balance normalised so a healthy account reads positive: assets net debits, liabilities
 *     net credits. Raw debits and credits are included because a balance without them is hard to
 *     argue with during a reconciliation.
 */
public record AccountBalance(
        String accountCode,
        UUID merchantId,
        String currency,
        AccountType accountType,
        long totalDebits,
        long totalCredits,
        long balance) {
}
