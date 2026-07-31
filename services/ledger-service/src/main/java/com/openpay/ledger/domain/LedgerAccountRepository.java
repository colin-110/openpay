package com.openpay.ledger.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LedgerAccountRepository extends JpaRepository<LedgerAccount, UUID> {

    /**
     * Derived queries cannot express "merchant_id IS NULL" and "merchant_id = ?" in one method, and
     * platform accounts genuinely have no merchant, so the null case is spelled out here.
     */
    @Query("""
            SELECT a FROM LedgerAccount a
            WHERE a.accountCode = :code
              AND a.currency = :currency
              AND ((:merchantId IS NULL AND a.merchantId IS NULL) OR a.merchantId = :merchantId)
            """)
    Optional<LedgerAccount> find(
            @Param("code") String accountCode,
            @Param("merchantId") UUID merchantId,
            @Param("currency") String currency);

    List<LedgerAccount> findByMerchantId(UUID merchantId);
}
