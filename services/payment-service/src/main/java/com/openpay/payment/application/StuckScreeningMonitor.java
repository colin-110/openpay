package com.openpay.payment.application;

import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.FraudCheckRequested;
import com.openpay.outbox.OutboxWriter;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Finds payments that asked for asynchronous screening and never got an answer, re-asks, and makes
 * the count visible.
 *
 * <p>This exists because asynchronous screening introduces a failure with no floor. In synchronous
 * mode an unreachable fraud-service resolves either way inside the request — it fails open and the
 * payment proceeds as {@code UNSCREENED}, or it fails closed and the merchant is told. In
 * asynchronous mode the payment is committed as {@code HELD} awaiting an event, and if that event
 * is never answered — the request landed in a dead-letter topic, the completion did, a consumer
 * was broken for long enough to matter — the payment simply waits. Forever. The merchant already
 * has its 201. Nothing retries, nothing alerts, and the money never moves.
 *
 * <p>That is the same class of failure the transactional outbox exists to prevent, reintroduced one
 * layer up, so it gets the same kind of answer: a sweep that re-drives the request.
 *
 * <p><strong>Re-asking is safe.</strong> {@code FraudService.screen} is idempotent on payment id
 * and returns the stored decision rather than making a second, possibly different one — which
 * matters, because the velocity window moves between deliveries and "screen it again properly"
 * would be a genuinely worse answer than "tell me what you already decided".
 *
 * <p><strong>Nothing here fails a payment automatically</strong>, and that is deliberate. A payment
 * stuck because a broker was down is a payment that is probably fine, and refusing it after a
 * timeout would turn an infrastructure problem into lost merchant revenue. Instead the count is a
 * gauge: past the deadline it is an alert, and an operator has the review queue to release from
 * and the dead-letter replay tool to re-drive with. Both already exist and are already tested; what
 * was missing was anything at all saying they were needed.
 *
 * <p>Registered only when asynchronous screening is switched on, because the hazard only exists in
 * that mode — in synchronous mode nothing ever sets the marker this reads.
 */
@Component
@ConditionalOnProperty(name = "openpay.fraud.async", havingValue = "true")
public class StuckScreeningMonitor {

    private static final Logger log = LoggerFactory.getLogger(StuckScreeningMonitor.class);
    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository paymentRepository;
    private final OutboxWriter outboxWriter;
    private final Duration deadline;
    private final int batchSize;
    /** Last observed count, published as a gauge. Micrometer samples this; it is never pushed. */
    private final AtomicLong stuckCount = new AtomicLong();

    public StuckScreeningMonitor(
            PaymentRepository paymentRepository,
            OutboxWriter outboxWriter,
            MeterRegistry meterRegistry,
            @Value("${openpay.fraud.async-deadline:PT2M}") Duration deadline,
            @Value("${openpay.fraud.async-sweep-batch:100}") int batchSize) {
        this.paymentRepository = paymentRepository;
        this.outboxWriter = outboxWriter;
        this.deadline = deadline;
        this.batchSize = batchSize;

        // A gauge, not a counter: the question an operator asks is "how many are stuck right now",
        // and a counter of sweep hits would keep climbing after the problem was fixed. Zero here
        // is the healthy reading, and it should be zero essentially always.
        Gauge.builder("openpay.payments.awaiting.screening", stuckCount, AtomicLong::doubleValue)
                .description("Payments waiting on asynchronous fraud screening past the deadline")
                .register(meterRegistry);

        log.info("Asynchronous screening is on; sweeping for payments stuck past {}", deadline);
    }

    @Scheduled(fixedDelayString = "${openpay.fraud.async-sweep-ms:30000}")
    @Transactional
    public void reDriveStalledScreening() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(deadline);

        // Counted separately from the batch, so the gauge reports the true size of the problem
        // rather than the size of one sweep. A gauge pinned at the batch size would read as
        // "exactly 100 stuck" no matter how bad it actually was.
        long total = paymentRepository.countByScreeningRequestedAtBefore(cutoff);
        stuckCount.set(total);
        if (total == 0) {
            return;
        }

        List<Payment> stalled = paymentRepository
                .findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(
                        cutoff, PageRequest.of(0, batchSize));

        log.warn("{} payment(s) have been awaiting screening for longer than {}; re-requesting {}",
                total, deadline, stalled.size());

        for (Payment payment : stalled) {
            // The marker is reset so this payment is not re-requested again on the very next sweep:
            // the deadline restarts from now, which turns the sweep into a bounded retry with a
            // fixed interval rather than a tight loop republishing the same events every 30s.
            payment.awaitAsynchronousScreening();
            outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.FRAUD_CHECK_REQUESTED, payment.getId(),
                    new FraudCheckRequested(
                            payment.getId(),
                            payment.getMerchantId(),
                            payment.getAmount(),
                            payment.getCurrency(),
                            payment.getPaymentMethod() == null
                                    ? null
                                    : payment.getPaymentMethod().getType()));
        }
    }
}
