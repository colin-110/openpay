package com.openpay.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The outbox relay is the seam the whole event backbone hangs off, and it had no test.
 *
 * <p>The behaviour worth pinning down is not "it publishes" — it is what happens when a send
 * fails partway through a batch. The relay stops at the first failure rather than continuing,
 * because continuing would publish later events for the same aggregate ahead of the one that
 * failed, and a consumer would see a payment captured before it was authorised. That ordering
 * guarantee is invisible in the happy path and is exactly what a refactor would quietly break.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    private static final int BATCH_SIZE = 100;

    @Mock
    private OutboxRepository repository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repository, kafkaTemplate, BATCH_SIZE, 10);
    }

    @Test
    void doesNothingWhenThereIsNothingPending() {
        when(repository.claimPendingBatch(BATCH_SIZE)).thenReturn(List.of());

        relay.publishPending();

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void publishesEveryPendingEventAndMarksItPublished() {
        OutboxEvent first = event("payment-1", "payments.created");
        OutboxEvent second = event("payment-2", "payments.created");
        when(repository.claimPendingBatch(BATCH_SIZE)).thenReturn(List.of(first, second));
        whenSendingSucceeds();

        relay.publishPending();

        verify(kafkaTemplate).send("payments.created", "payment-1", first.getPayload());
        verify(kafkaTemplate).send("payments.created", "payment-2", second.getPayload());
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void keysEachMessageByAggregateIdSoOnePaymentStaysOnOnePartition() {
        // Ordering within a payment depends entirely on this: Kafka only guarantees order within
        // a partition, and the aggregate id is what pins every event for one payment to one.
        OutboxEvent event = event("payment-42", "payments.created");
        when(repository.claimPendingBatch(BATCH_SIZE)).thenReturn(List.of(event));
        whenSendingSucceeds();

        relay.publishPending();

        verify(kafkaTemplate).send(anyString(), eq("payment-42"), anyString());
    }

    @Test
    void stopsAtTheFirstFailureRatherThanSkippingPastIt() {
        OutboxEvent first = event("payment-1", "payments.created");
        OutboxEvent second = event("payment-1", "payments.authorized");
        OutboxEvent third = event("payment-1", "payments.captured");
        when(repository.claimPendingBatch(BATCH_SIZE)).thenReturn(List.of(first, second, third));

        when(kafkaTemplate.send(eq("payments.created"), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(kafkaTemplate.send(eq("payments.authorized"), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending();

        assertThat(first.getPublishedAt()).isNotNull();
        // The one that failed is left unpublished with its attempt counted, so the next poll
        // retries from exactly here.
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isEqualTo(1);
        // And the one behind it is untouched — publishing a capture while its authorisation is
        // still stuck would hand a consumer the payment's history out of order.
        assertThat(third.getPublishedAt()).isNull();
        assertThat(third.getAttempts()).isZero();
        verify(kafkaTemplate, never()).send(eq("payments.captured"), anyString(), anyString());
    }

    @Test
    void countsAnAttemptEveryTimeAnEventFailsSoRepeatedFailuresAreVisible() {
        OutboxEvent event = event("payment-1", "payments.created");
        when(repository.claimPendingBatch(BATCH_SIZE)).thenReturn(List.of(event));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        relay.publishPending();
        relay.publishPending();
        relay.publishPending();

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getPublishedAt()).isNull();
    }

    @SuppressWarnings("unchecked")
    private void whenSendingSucceeds() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));
    }

    private OutboxEvent event(String aggregateId, String topic) {
        return new OutboxEvent("Payment", aggregateId, topic, "{\"eventId\":\"e\"}", "corr-1");
    }
}
