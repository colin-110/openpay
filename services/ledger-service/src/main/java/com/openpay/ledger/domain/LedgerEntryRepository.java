package com.openpay.ledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

    List<LedgerEntry> findByTransactionId(UUID transactionId);

    Page<LedgerEntry> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable);

    /**
     * Sums an account from the journal rather than keeping a running total column. A stored balance
     * is a second source of truth that can drift from the entries; deriving it cannot.
     */
    @Query("""
            SELECT COALESCE(SUM(CASE WHEN e.direction = com.openpay.ledger.domain.EntryDirection.DEBIT
                                     THEN e.amount ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN e.direction = com.openpay.ledger.domain.EntryDirection.CREDIT
                                     THEN e.amount ELSE 0 END), 0)
            FROM LedgerEntry e WHERE e.accountId = :accountId
            """)
    Object[] sumDebitsAndCredits(@Param("accountId") UUID accountId);
}
