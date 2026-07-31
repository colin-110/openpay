package com.openpay.ledger.application;

import com.openpay.ledger.domain.AccountType;
import com.openpay.ledger.domain.EntryDirection;
import java.util.List;
import java.util.UUID;

/**
 * A balanced set of entries to post as one transaction.
 *
 * @param eventId the event that caused this posting; posting twice for one event is refused
 */
public record PostingRequest(
        UUID eventId,
        String referenceType,
        UUID referenceId,
        String currency,
        String description,
        List<Line> lines) {

    /**
     * @param merchantId null for platform-owned accounts
     * @param amount positive, in minor units
     */
    public record Line(
            String accountCode,
            UUID merchantId,
            AccountType accountType,
            EntryDirection direction,
            long amount) {
    }
}
