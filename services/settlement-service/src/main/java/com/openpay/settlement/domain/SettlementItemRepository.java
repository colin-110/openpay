package com.openpay.settlement.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {

    Optional<SettlementItem> findByPaymentIdAndItemType(UUID paymentId, SettlementItemType itemType);

    Optional<SettlementItem> findByRefundId(UUID refundId);

    List<SettlementItem> findBySettlementId(UUID settlementId);

    /**
     * Everything eligible for payout: accrued, not yet batched, and past the hold period.
     *
     * <p>Locked with SKIP LOCKED so two settlement runs cannot batch the same item into two
     * different payouts.
     */
    @Query(value = """
            SELECT * FROM settlement_items
            WHERE status = 'PENDING' AND captured_at <= :eligibleBefore
            ORDER BY merchant_id, currency, captured_at
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<SettlementItem> claimEligible(@Param("eligibleBefore") OffsetDateTime eligibleBefore);
}
