package com.openpay.events.payload;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A payout batch was created and the merchant's payable should now be cleared.
 *
 * <p>All three amounts travel together because the ledger posting needs each of them: the gross
 * clears what we owed, the fee becomes platform revenue, and the net is what actually leaves.
 */
public record SettlementCreated(
        UUID settlementId,
        UUID merchantId,
        String currency,
        LocalDate settlementDate,
        long grossAmount,
        long feeAmount,
        long netAmount,
        int itemCount) {
}
