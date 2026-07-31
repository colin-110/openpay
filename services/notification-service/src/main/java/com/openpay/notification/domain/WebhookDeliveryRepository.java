package com.openpay.notification.domain;

import java.time.OffsetDateTime;
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
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, UUID> {

    Optional<WebhookDelivery> findByEventId(UUID eventId);

    Page<WebhookDelivery> findByMerchantIdOrderByCreatedAtDesc(UUID merchantId, Pageable pageable);

    Page<WebhookDelivery> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Claims deliveries that are due.
     *
     * <p>SKIP LOCKED so several dispatcher replicas share the work instead of every one of them
     * sending the same webhook, which would spam the merchant rather than merely duplicating an
     * internal event.
     */
    @Query(value = """
            SELECT * FROM merchant_webhook_deliveries
            WHERE status = 'PENDING' AND next_attempt_at <= :now
            ORDER BY next_attempt_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<WebhookDelivery> claimDue(
            @Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);
}
