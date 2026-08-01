package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.openpay.events.OpenPayTopics;
import com.openpay.events.dlq.DeadLetterAdmin;
import com.openpay.events.dlq.DeadLetterRecord;
import com.openpay.events.dlq.UnknownDeadLetterTopicException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The replay tool against a real broker.
 *
 * <p>A real Kafka because there is nothing else worth testing here. Dead-lettering is entirely the
 * broker's behaviour plus Spring Kafka's error handler; against a mocked template this test would
 * only prove that its own assumptions agree with themselves.
 *
 * <p>Retries are turned down to one, so a poison message reaches the DLQ in about a second instead
 * of four.
 */
@SpringBootTest(properties = {
        "openpay.outbox.relay-enabled=false",
        "openpay.kafka.error-handling.max-retries=1",
        "openpay.kafka.error-handling.retry-interval-ms=200",
        "openpay.dlq.enabled=true"
})
@Testcontainers
class DeadLetterReplayIT {

    private static final String POISONED_TOPIC = OpenPayTopics.PROVIDER_CALLBACK_RECEIVED;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:4.0.0");

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private DeadLetterAdmin deadLetterAdmin;

    @Test
    void anUnprocessableMessageEndsUpInTheDeadLetterTopicAndCanBeReplayed() {
        String key = UUID.randomUUID().toString();
        // Not valid for this topic's schema, so the listener throws every time it is delivered.
        // Spring's default would have retried ten times and dropped it; the point of the DLQ is
        // that it is still here afterwards.
        kafkaTemplate.send(POISONED_TOPIC, key, "{\"this\":\"is not an event envelope\"}");

        List<DeadLetterRecord> waiting = await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> deadLetterAdmin.peek(POISONED_TOPIC, 10), records ->
                        records.stream().anyMatch(record -> key.equals(record.key())));

        DeadLetterRecord dead = waiting.stream()
                .filter(record -> key.equals(record.key()))
                .findFirst()
                .orElseThrow();
        // The failure travels with the message. Without it an operator has a payload and no idea
        // why it is here.
        assertThat(dead.originalTopic()).isEqualTo(POISONED_TOPIC);
        assertThat(dead.exceptionMessage()).isNotBlank();
        assertThat(dead.payload()).contains("not an event envelope");

        // Peeking twice must show the same thing: looking has to be free, or nobody will look
        // before deciding what to replay.
        assertThat(deadLetterAdmin.peek(POISONED_TOPIC, 10))
                .anyMatch(record -> record.offset() == dead.offset());

        assertThat(deadLetterAdmin.replay(POISONED_TOPIC, 10)).isPositive();

        // That offset is committed and will never be offered again.
        assertThat(deadLetterAdmin.peek(POISONED_TOPIC, 10))
                .noneMatch(record -> record.offset() == dead.offset());

        // But the message itself comes straight back, at a new offset, because nothing about it was
        // fixed — it was never going to parse. That is the honest outcome, and it is exactly why
        // discard is a separate operation: replay is for messages whose cause has been dealt with,
        // and using it to clear a queue just moves the poison along by one offset.
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofSeconds(2))
                .until(() -> deadLetterAdmin.peek(POISONED_TOPIC, 10), records ->
                        records.stream().anyMatch(record -> key.equals(record.key())));

        assertThat(deadLetterAdmin.discard(POISONED_TOPIC, 10)).isPositive();
        assertThat(deadLetterAdmin.peek(POISONED_TOPIC, 10)).isEmpty();
    }

    @Test
    void aTopicThisServiceDoesNotHandleIsRefused() {
        // Replay publishes to a topic derived from the request. Without the allowlist, the ops
        // token would be enough to inject an event into any topic on the platform.
        assertThatThrownBy(() -> deadLetterAdmin.peek("settlement.created.v1", 10))
                .isInstanceOf(UnknownDeadLetterTopicException.class);
        assertThatThrownBy(() -> deadLetterAdmin.replay("../../etc/passwd", 10))
                .isInstanceOf(UnknownDeadLetterTopicException.class);
    }

    @Test
    void anEmptyDeadLetterTopicReplaysNothing() {
        assertThat(deadLetterAdmin.replay(OpenPayTopics.REFUND_CALLBACK_RECEIVED, 10)).isZero();
        assertThat(deadLetterAdmin.discard(OpenPayTopics.REFUND_CALLBACK_RECEIVED, 10)).isZero();
    }

    @Test
    void theKnownTopicsAreTheOnesThisServiceConsumes() {
        assertThat(deadLetterAdmin.knownTopics()).contains(
                OpenPayTopics.PROVIDER_CALLBACK_RECEIVED,
                OpenPayTopics.PAYMENT_PROVIDER_DISPATCHED,
                OpenPayTopics.REFUND_CALLBACK_RECEIVED,
                OpenPayTopics.FRAUD_CHECK_COMPLETED);
    }
}
