package com.openpay.events;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Wrapper carried by every OpenPay event.
 *
 * <p>The metadata is what makes at-least-once delivery workable: {@code eventId} lets a consumer
 * detect a redelivery, {@code correlationId} ties an asynchronous hop back to the request that
 * started it, and {@code aggregateId} is the partition key so events for one payment stay ordered.
 *
 * @param payload the event body; its shape is defined by the type named in {@code eventType}
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        String aggregateId,
        String correlationId,
        OffsetDateTime occurredAt,
        T payload) {

    public static <T> EventEnvelope<T> of(String eventType, String aggregateId, String correlationId, T payload) {
        return new EventEnvelope<>(
                UUID.randomUUID(), eventType, aggregateId, correlationId, OffsetDateTime.now(), payload);
    }
}
