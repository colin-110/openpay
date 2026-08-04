package com.openpay.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.notification.application.DeliveryQueue;
import com.openpay.notification.application.WebhookDispatcher;
import com.openpay.notification.domain.DeliveryStatus;
import com.openpay.notification.domain.WebhookDelivery;
import com.openpay.notification.domain.WebhookDeliveryRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The ops alert fired when a webhook delivery is abandoned — against a real SMTP server, the same
 * reasoning {@code SecurityAlertEmailIT} in auth-service uses.
 *
 * <p>{@code max-attempts=1} and no merchant-service running is the whole setup: the very first
 * dispatch attempt cannot reach a merchant config, which is a real failure this dispatcher already
 * has to handle, and with one attempt allowed it abandons on the spot rather than needing several
 * scheduler passes to get there.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "openpay.notification.poll-interval-ms=3600000",
        "openpay.notification.max-attempts=1",
        "openpay.notification.ops-email=ops@openpay.test"
})
@Testcontainers
class AbandonedDeliveryEmailIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    static GenericContainer<?> mailpit =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.22"))
                    .withExposedPorts(1025, 8025);

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", mailpit::getHost);
        registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
    }

    @Autowired
    private DeliveryQueue deliveryQueue;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Autowired
    private WebhookDispatcher webhookDispatcher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void anAbandonedDeliveryEmailsAnOperator() throws Exception {
        UUID merchantId = UUID.randomUUID();
        WebhookDelivery delivery = deliveryQueue
                .enqueue(merchantId, UUID.randomUUID(), "payment.captured", Map.of("a", 1))
                .orElseThrow();

        // No merchant-service to answer this, so the attempt fails before it ever reaches a URL —
        // with max-attempts=1, that single failure is also the one that abandons it.
        webhookDispatcher.dispatchDue();

        WebhookDelivery reloaded = deliveryRepository.findById(delivery.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(DeliveryStatus.ABANDONED);

        // Thirty seconds for the same reason as SecurityAlertEmailIT: this waits on an
        // asynchronous email crossing a container boundary, and a ten-second budget only holds on
        // an idle machine. Polling means a healthy run is no slower for the larger number.
        JsonNode message = await()
                .atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(this::findLatestMessage, (m) -> m != null);

        assertThat(message.path("Subject").asText()).contains("Webhook delivery abandoned");
        assertThat(message.path("To").get(0).path("Address").asText()).isEqualTo("ops@openpay.test");
    }

    private JsonNode findLatestMessage() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025)
                        + "/api/v1/messages"))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode messages = objectMapper.readTree(response.body()).path("messages");
        return messages.isEmpty() ? null : messages.get(0);
    }
}
