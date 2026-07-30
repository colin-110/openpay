package com.openpay.payment.outbox;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.observability.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Appends an event to the outbox inside the caller's transaction.
 *
 * <p>There is no {@code @Transactional} here on purpose: this must join the transaction that is
 * writing the business row, not open one of its own.
 */
@Component
public class OutboxWriter {

    private static final String AGGREGATE_TYPE = "payment";

    private final OutboxRepository outboxRepository;
    private final EventCodec eventCodec;

    public OutboxWriter(OutboxRepository outboxRepository, EventCodec eventCodec) {
        this.outboxRepository = outboxRepository;
        this.eventCodec = eventCodec;
    }

    public void append(String topic, UUID aggregateId, Object payload) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        EventEnvelope<Object> envelope =
                EventEnvelope.of(topic, aggregateId.toString(), correlationId, payload);

        outboxRepository.save(new OutboxEvent(
                AGGREGATE_TYPE, aggregateId.toString(), topic, eventCodec.encode(envelope), correlationId));
    }
}
