package com.openpay.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Encodes envelopes as plain JSON strings.
 *
 * <p>Deliberately not Spring Kafka's {@code JsonSerializer}: that writes Java class names into
 * message headers, which couples producer and consumer to identical package structures and breaks
 * the moment a service is renamed or reimplemented in another language. A self-describing JSON
 * body keeps the contract in the payload where other teams can read it.
 */
public class EventCodec {

    private final ObjectMapper objectMapper;

    public EventCodec() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // ISO-8601 timestamps, so a human reading a topic can tell what happened when.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                // Tolerate fields added by a newer producer rather than failing the consumer.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String encode(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new EventCodecException("Could not encode event", exception);
        }
    }

    public <T> EventEnvelope<T> decode(String json, Class<T> payloadType) {
        try {
            return objectMapper.readValue(
                    json, objectMapper.getTypeFactory()
                            .constructParametricType(EventEnvelope.class, payloadType));
        } catch (Exception exception) {
            throw new EventCodecException("Could not decode event", exception);
        }
    }

    public <T> T decodePayload(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception exception) {
            throw new EventCodecException("Could not decode payload", exception);
        }
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public static class EventCodecException extends RuntimeException {
        public EventCodecException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
