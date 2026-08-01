package com.openpay.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * How far behind the outbox relay is.
 *
 * <p>Arguably the most important number this platform publishes. Everything after payment creation
 * is driven by events, so a relay that has stalled does not fail anything: payments are accepted,
 * committed, and then simply stop advancing. There is no error to alert on, and the first signal
 * without this gauge is a merchant asking why nothing has settled.
 *
 * <p>The count is sampled on a schedule rather than computed when Prometheus scrapes. A gauge
 * backed directly by a {@code COUNT(*)} would run a query per scrape, per replica, against the same
 * table the relay is trying to claim rows from — and a slow scrape would then look like a slow
 * relay.
 */
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository outboxRepository;
    private final AtomicLong unpublished = new AtomicLong();

    public OutboxMetrics(OutboxRepository outboxRepository, MeterRegistry meterRegistry) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("openpay.outbox.unpublished", unpublished, AtomicLong::get)
                .description("Events written to the outbox and not yet published to Kafka")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${openpay.outbox.metrics-interval:PT10S}")
    public void sample() {
        try {
            unpublished.set(outboxRepository.countByPublishedAtIsNull());
        } catch (RuntimeException exception) {
            // A failed sample must not take the scheduler down with it, or the metric stops
            // updating permanently after one blip and then quietly reads as healthy.
            log.warn("Could not sample the outbox backlog", exception);
        }
    }
}
