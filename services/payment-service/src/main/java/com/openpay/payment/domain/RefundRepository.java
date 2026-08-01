package com.openpay.payment.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    Optional<Refund> findByIdAndMerchantId(UUID id, UUID merchantId);

    List<Refund> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Page<Refund> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<Refund> findByMerchantIdAndStatusOrderByCreatedAtDesc(
            UUID merchantId, RefundStatus status, Pageable pageable);

    /**
     * What a payment has already committed to refunding.
     *
     * <p>PENDING counts towards the total on purpose. A refund in flight has money on its way out,
     * so ignoring it would let a merchant fire several concurrent requests and refund more than
     * the payment was ever worth. FAILED is excluded, which releases the amount again.
     */
    @Query("""
            SELECT COALESCE(SUM(r.amount), 0) FROM Refund r
            WHERE r.paymentId = :paymentId
              AND r.status <> com.openpay.payment.domain.RefundStatus.FAILED
            """)
    long sumCommittedAmount(@Param("paymentId") UUID paymentId);
}
