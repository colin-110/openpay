package com.openpay.payment.outbox;

import java.time.Duration;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Trims published outbox rows.
 *
 * <p>The retention window is deliberately generous rather than immediate: keeping recently
 * published rows around is what lets you answer "did we actually emit that event, and when"
 * during an incident. Past that window {@code payment_events} remains the durable history.
 */
@Component
public class OutboxRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);

    private final OutboxRepository outboxRepository;
    private final Duration retention;

    public OutboxRetentionJob(
            OutboxRepository outboxRepository,
            @Value("${openpay.outbox.retention:P7D}") Duration retention) {
        this.outboxRepository = outboxRepository;
        this.retention = retention;
    }

    @Scheduled(fixedDelayString = "${openpay.outbox.retention-sweep-ms:3600000}")
    @Transactional
    public void purgePublished() {
        int deleted = outboxRepository.deletePublishedBefore(OffsetDateTime.now().minus(retention));
        if (deleted > 0) {
            log.info("Purged {} outbox events published more than {} ago", deleted, retention);
        }
    }
}
