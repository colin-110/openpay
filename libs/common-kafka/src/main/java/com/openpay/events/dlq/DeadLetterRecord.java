package com.openpay.events.dlq;

import java.time.OffsetDateTime;

/**
 * One message sitting in a dead-letter topic, as an operator needs to see it.
 *
 * <p>{@code payload} is the message body verbatim. It is included because deciding whether a
 * message is safe to replay usually means reading it, and an operator who has to go to the broker
 * with a console consumer to do that will replay blind instead.
 */
public record DeadLetterRecord(
        String deadLetterTopic,
        String originalTopic,
        int partition,
        long offset,
        String key,
        String payload,
        /** What the consumer threw, taken from the headers Spring Kafka wrote when it gave up. */
        String exceptionType,
        String exceptionMessage,
        OffsetDateTime timestamp) {
}
