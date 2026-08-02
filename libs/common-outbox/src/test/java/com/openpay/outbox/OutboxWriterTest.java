package com.openpay.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.openpay.events.EventCodec;
import com.openpay.observability.CorrelationIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.MDC;

/**
 * The write half of the outbox. What matters here is what ends up in the row: the relay reads
 * {@code topic}, {@code aggregateId} and {@code payload} back out and sends them to Kafka
 * verbatim, so anything wrong at this point is wrong on the wire too.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxWriterTest {

    @Mock
    private OutboxRepository repository;

    private OutboxWriter writer;
    private EventCodec eventCodec;

    @BeforeEach
    void setUp() {
        eventCodec = new EventCodec();
        writer = new OutboxWriter(repository, eventCodec);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void writesTheEventOntoTheTopicKeyedByItsAggregate() {
        UUID paymentId = UUID.randomUUID();

        writer.append("payment", "payments.created", paymentId, new SamplePayload("CREATED", 2500L));

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTopic()).isEqualTo("payments.created");
        assertThat(saved.getAggregateId()).isEqualTo(paymentId.toString());
    }

    @Test
    void storesTheEnvelopeAsJsonThePayloadCanBeReadBackOutOf() throws Exception {
        UUID paymentId = UUID.randomUUID();

        writer.append("payment", "payments.created", paymentId, new SamplePayload("CREATED", 2500L));

        JsonNode envelope = eventCodec.objectMapper().readTree(captureSaved().getPayload());
        assertThat(envelope.get("payload").get("status").asText()).isEqualTo("CREATED");
        assertThat(envelope.get("payload").get("amount").asLong()).isEqualTo(2500L);
        // Every envelope carries an eventId because delivery is at-least-once: it is what a
        // consumer deduplicates on when the relay replays after a crash.
        assertThat(envelope.hasNonNull("eventId")).isTrue();
    }

    @Test
    void carriesTheCorrelationIdFromTheRequestThatCausedTheEvent() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-xyz");

        writer.append("payment", "payments.created", UUID.randomUUID(), new SamplePayload("CREATED", 1L));

        assertThat(captureSaved().getCorrelationId()).isEqualTo("corr-xyz");
    }

    @Test
    void writesAnEventEvenWhenThereIsNoCorrelationIdToCarry() {
        // A scheduled job has no inbound request. Losing the event because it also has no
        // correlation id would be the wrong trade entirely.
        writer.append("settlement", "settlements.created", UUID.randomUUID(), new SamplePayload("BATCHED", 9L));

        OutboxEvent saved = captureSaved();
        assertThat(saved.getCorrelationId()).isNull();
        assertThat(saved.getTopic()).isEqualTo("settlements.created");
    }

    @Test
    void startsEveryEventUnpublishedWithNoAttemptsRecorded() {
        writer.append("payment", "payments.created", UUID.randomUUID(), new SamplePayload("CREATED", 1L));

        OutboxEvent saved = captureSaved();
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getAttempts()).isZero();
    }

    private OutboxEvent captureSaved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private record SamplePayload(String status, Long amount) {
    }
}
