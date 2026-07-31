package com.openpay.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of unpublished events for this instance only.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes the relay safe to run on more than one
     * replica. Without it every instance reads the same unpublished rows and every instance
     * publishes them, so scaling payment-service out would silently multiply every event.
     * {@code SKIP LOCKED} lets a second instance step over rows a first has claimed instead of
     * blocking behind them.
     *
     * <p>Must be called inside a transaction; the locks are held until it commits.
     */
    @Query(value = """
            SELECT * FROM outbox_events
            WHERE published_at IS NULL
            ORDER BY created_at
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("batchSize") int batchSize);

    /**
     * Drops published events past their retention window. The outbox is a delivery mechanism, not
     * an audit log; payment_events is the durable history, so keeping every relayed row forever
     * only grows a table that is on the hot path of the relay's index scan.
     */
    @Modifying
    @Query("DELETE FROM OutboxEvent o WHERE o.publishedAt IS NOT NULL AND o.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") OffsetDateTime cutoff);

    long countByPublishedAtIsNull();
}
