package com.openpay.ledger.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, UUID> {

    Optional<LedgerTransaction> findByEventId(UUID eventId);

    List<LedgerTransaction> findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
            String referenceType, UUID referenceId);
}
