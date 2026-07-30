package com.openpay.payment.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest first, so events for one payment reach Kafka in the order they happened. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByCreatedAtAsc(Limit limit);

    long countByPublishedAtIsNull();
}
