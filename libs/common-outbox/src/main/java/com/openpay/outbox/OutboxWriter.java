package com.openpay.outbox;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.observability.CorrelationIdFilter;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * Appends an event to the outbox inside the caller's transaction.
 *
 * <p>There is no {@code @Transactional} here on purpose: this must join the transaction that is
 * writing the business row, not open one of its own.
 */
public class OutboxWriter {

    private final OutboxRepository outboxRepository;
    private final EventCodec eventCodec;

    public OutboxWriter(OutboxRepository outboxRepository, EventCodec eventCodec) {
        this.outboxRepository = outboxRepository;
        this.eventCodec = eventCodec;
    }

    /**
     * @param aggregateType what the id refers to, e.g. "payment" or "settlement"; carried so an
     *     operator reading the table can tell what a stuck row belongs to
     */
    public void append(String aggregateType, String topic, UUID aggregateId, Object payload) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        EventEnvelope<Object> envelope =
                EventEnvelope.of(topic, aggregateId.toString(), correlationId, payload);

        outboxRepository.save(new OutboxEvent(
                aggregateType, aggregateId.toString(), topic, eventCodec.encode(envelope), correlationId));
    }
}
