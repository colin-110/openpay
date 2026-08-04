package com.openpay.payment.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByMerchantIdAndIdempotencyKey(UUID merchantId, String idempotencyKey);

    /** Scoped by merchant so one merchant can never read another merchant's payment. */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    Page<Payment> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<Payment> findByMerchantIdAndStatusOrderByCreatedAtDesc(
            UUID merchantId, PaymentStatus status, Pageable pageable);

    /**
     * Payments that have been waiting on asynchronous screening for too long.
     *
     * <p>Ordered oldest first so a bounded sweep always makes progress on the worst cases rather
     * than re-reading the same recent ones. Backed by the partial index from V10, which covers
     * only rows where the marker is set — nearly always none.
     */
    List<Payment> findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(
            OffsetDateTime deadline, Pageable pageable);

    long countByScreeningRequestedAtBefore(OffsetDateTime deadline);
}
