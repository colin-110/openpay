package com.openpay.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.notification.application.DeliveryQueue;
import com.openpay.notification.domain.DeliveryStatus;
import com.openpay.notification.domain.WebhookDelivery;
import com.openpay.notification.domain.WebhookDeliveryRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // The dispatcher would try to reach merchant-service and real merchant URLs.
        "openpay.notification.poll-interval-ms=3600000"
})
@Testcontainers
class DeliveryQueueIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private DeliveryQueue deliveryQueue;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Test
    void queuesADeliveryReadyToSendImmediately() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        WebhookDelivery delivery = deliveryQueue
                .enqueue(merchantId, eventId, "payment.captured", Map.of("amount", 10_000))
                .orElseThrow();

        assertThat(delivery.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(delivery.getAttempts()).isZero();
        assertThat(delivery.getNextAttemptAt()).isNotNull();
        assertThat(delivery.getPayload()).contains("10000");
    }

    @Test
    void oneSourceEventProducesOneDelivery() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        WebhookDelivery first = deliveryQueue
                .enqueue(merchantId, eventId, "payment.captured", Map.of("a", 1)).orElseThrow();
        WebhookDelivery second = deliveryQueue
                .enqueue(merchantId, eventId, "payment.captured", Map.of("a", 1)).orElseThrow();

        // A merchant seeing the same notification twice is more visible than most duplication bugs.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(deliveryRepository.findByEventId(eventId)).isPresent();
    }

    @Test
    void aDueDeliveryIsClaimedAndOneScheduledLaterIsNot() {
        UUID merchantId = UUID.randomUUID();
        deliveryQueue.enqueue(merchantId, UUID.randomUUID(), "payment.captured", Map.of("a", 1));

        assertThat(deliveryRepository.claimDue(OffsetDateTime.now(), 50))
                .anyMatch(delivery -> delivery.getMerchantId().equals(merchantId));
        assertThat(deliveryRepository.claimDue(OffsetDateTime.now().minusHours(1), 50))
                .noneMatch(delivery -> delivery.getMerchantId().equals(merchantId));
    }

    @Test
    void aFailedAttemptIsRescheduledRatherThanLost() {
        WebhookDelivery delivery = deliveryQueue
                .enqueue(UUID.randomUUID(), UUID.randomUUID(), "payment.captured", Map.of("a", 1))
                .orElseThrow();

        delivery.recordFailure("https://merchant.test/hook", 500, "merchant returned 500",
                Duration.ofSeconds(30), 8);
        deliveryRepository.save(delivery);

        WebhookDelivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        assertThat(reloaded.getNextAttemptAt()).isAfter(OffsetDateTime.now());
        assertThat(reloaded.getLastError()).contains("500");
    }

    @Test
    void deliveryIsAbandonedOnceAttemptsRunOut() {
        WebhookDelivery delivery = deliveryQueue
                .enqueue(UUID.randomUUID(), UUID.randomUUID(), "payment.captured", Map.of("a", 1))
                .orElseThrow();

        for (int i = 0; i < 3; i++) {
            delivery.recordFailure("https://merchant.test/hook", 500, "down", Duration.ofSeconds(1), 3);
        }
        deliveryRepository.save(delivery);

        WebhookDelivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        // Abandoned rather than deleted: a merchant who never got told must remain findable.
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.ABANDONED);
        assertThat(reloaded.getNextAttemptAt()).isNull();
        assertThat(deliveryRepository.claimDue(OffsetDateTime.now(), 50))
                .noneMatch(candidate -> candidate.getId().equals(delivery.getId()));
    }

    @Test
    void aDeliveredWebhookIsNeverSentAgain() {
        WebhookDelivery delivery = deliveryQueue
                .enqueue(UUID.randomUUID(), UUID.randomUUID(), "payment.captured", Map.of("a", 1))
                .orElseThrow();

        delivery.recordSuccess("https://merchant.test/hook", 200);
        deliveryRepository.save(delivery);

        WebhookDelivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(reloaded.getDeliveredAt()).isNotNull();
        assertThat(deliveryRepository.claimDue(OffsetDateTime.now(), 50))
                .noneMatch(candidate -> candidate.getId().equals(delivery.getId()));
    }

    @Test
    void payloadSurvivesTheJsonbRoundTrip() {
        UUID eventId = UUID.randomUUID();
        deliveryQueue.enqueue(UUID.randomUUID(), eventId, "refund.succeeded",
                Map.of("refundId", "abc", "amount", 2_500));

        Optional<WebhookDelivery> stored = deliveryRepository.findByEventId(eventId);
        assertThat(stored).isPresent();
        assertThat(stored.get().getPayload()).contains("2500").contains("abc");
    }
}
