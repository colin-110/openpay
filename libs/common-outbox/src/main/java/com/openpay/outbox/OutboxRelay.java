package com.openpay.outbox;

import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes committed outbox rows to Kafka.
 *
 * <p>Delivery is at-least-once: a row is marked published only after the broker acknowledges it, so
 * a crash between the send and the update replays the event. Consumers are expected to be
 * idempotent, which is why every envelope carries an {@code eventId}.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;
    private final long sendTimeoutSeconds;

    public OutboxRelay(
            OutboxRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            @Value("${openpay.outbox.batch-size:100}") int batchSize,
            @Value("${openpay.outbox.send-timeout-seconds:10}") long sendTimeoutSeconds) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.sendTimeoutSeconds = sendTimeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${openpay.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishPending() {
        // Claims rows with FOR UPDATE SKIP LOCKED, so running several replicas of this service
        // divides the work rather than publishing everything N times.
        List<OutboxEvent> pending = outboxRepository.claimPendingBatch(batchSize);
        if (pending.isEmpty()) {
            return;
        }

        for (OutboxEvent event : pending) {
            try {
                // Keyed by aggregate id so all events for one payment land on the same partition
                // and stay in order.
                kafkaTemplate
                        .send(event.getTopic(), event.getAggregateId(), event.getPayload())
                        .get(sendTimeoutSeconds, TimeUnit.SECONDS);
                event.markPublished();
                log.debug("Published outbox event {} to {}", event.getId(), event.getTopic());
            } catch (Exception exception) {
                // Stop at the first failure: continuing would publish later events for the same
                // aggregate ahead of this one. The next poll retries from here.
                event.markFailed(exception.getMessage());
                log.warn("Could not publish outbox event {} (attempt {}), will retry",
                        event.getId(), event.getAttempts(), exception);
                if (Thread.currentThread().isInterrupted()) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
        }
    }
}
