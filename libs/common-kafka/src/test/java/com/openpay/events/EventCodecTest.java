package com.openpay.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * {@link EventCodec} is the wire contract between every service on the platform, and it had no
 * test. Each case here is a property another service depends on rather than a detail of Jackson:
 * a consumer must survive a producer that added a field, a human reading a topic must see a
 * readable timestamp, and nothing Java-specific may leak into the body — that last one is the
 * entire reason this class exists instead of Spring Kafka's {@code JsonSerializer}.
 */
class EventCodecTest {

    private final EventCodec codec = new EventCodec();

    @Test
    void roundTripsAnEnvelopeWithItsPayloadIntact() {
        UUID paymentId = UUID.randomUUID();
        EventEnvelope<SamplePayload> original = EventEnvelope.of(
                "payments.created", paymentId.toString(), "corr-1", new SamplePayload("CREATED", 2500L));

        EventEnvelope<SamplePayload> decoded = codec.decode(codec.encode(original), SamplePayload.class);

        assertThat(decoded.eventId()).isEqualTo(original.eventId());
        assertThat(decoded.aggregateId()).isEqualTo(paymentId.toString());
        assertThat(decoded.correlationId()).isEqualTo("corr-1");
        assertThat(decoded.payload()).isEqualTo(new SamplePayload("CREATED", 2500L));
    }

    @Test
    void toleratesAFieldAddedByANewerProducer() {
        // The one that matters for rolling deploys: a producer upgraded ahead of its consumers
        // must not take them down. Failing here would mean every schema addition is a lockstep
        // release across every service that reads the topic.
        String fromNewerProducer = """
                {"eventId":"%s","eventType":"payments.created","aggregateId":"a-1",
                 "correlationId":"corr-1","occurredAt":"2026-01-01T00:00:00Z",
                 "payload":{"status":"CREATED","amount":2500,"somethingNew":"ignored"},
                 "aFieldTheEnvelopeDoesNotHave":true}
                """.formatted(UUID.randomUUID());

        EventEnvelope<SamplePayload> decoded = codec.decode(fromNewerProducer, SamplePayload.class);

        assertThat(decoded.payload().status()).isEqualTo("CREATED");
        assertThat(decoded.payload().amount()).isEqualTo(2500L);
    }

    @Test
    void writesTimestampsAsIso8601RatherThanEpochNumbers() {
        String json = codec.encode(
                EventEnvelope.of("payments.created", "a-1", "corr-1", new SamplePayload("CREATED", 1L)));

        // A number here would still round-trip, but nobody reading the topic during an incident
        // could tell what time it happened without converting it first.
        assertThat(json).containsPattern("\"occurredAt\":\"\\d{4}-\\d{2}-\\d{2}T");
    }

    @Test
    void leaksNoJavaTypeInformationIntoTheBody() {
        // Spring Kafka's JsonSerializer writes class names into headers, which couples producer
        // and consumer to identical package structures. Any of it appearing here would mean a
        // consumer in another language could not read the topic.
        String json = codec.encode(
                EventEnvelope.of("payments.created", "a-1", "corr-1", new SamplePayload("CREATED", 1L)));

        assertThat(json).doesNotContain("com.openpay").doesNotContain("@class").doesNotContain("SamplePayload");
    }

    @Test
    void refusesToDecodeSomethingThatIsNotAnEnvelope() {
        assertThatThrownBy(() -> codec.decode("not json at all", SamplePayload.class))
                .isInstanceOf(EventCodec.EventCodecException.class)
                .hasMessageContaining("Could not decode event");
    }

    @Test
    void refusesToEncodeSomethingItCannotSerialise() {
        assertThatThrownBy(() -> codec.encode(new Unserialisable()))
                .isInstanceOf(EventCodec.EventCodecException.class)
                .hasMessageContaining("Could not encode event");
    }

    private record SamplePayload(String status, Long amount) {
    }

    /** No properties and no accessors, which Jackson refuses to serialise by default. */
    private static class Unserialisable {
    }
}
