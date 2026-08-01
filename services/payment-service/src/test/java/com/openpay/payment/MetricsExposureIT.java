package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.infrastructure.FraudScreeningClient;
import com.openpay.payment.infrastructure.FraudScreeningClient.ScreeningOutcome;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The dashboards are only as good as the metrics behind them.
 *
 * <p>This scrapes the real endpoint and asserts the series the Payment Flow dashboard queries
 * actually exist and carry the labels it groups by. Without it, a renamed metric is a panel that
 * silently reads "No data" — which looks exactly like a healthy system with no traffic.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "openpay.outbox.relay-enabled=false",
                "spring.kafka.listener.auto-startup=false"
        })
// Spring Boot's test support disables metrics export by default, so /actuator/prometheus is
// absent in a plain @SpringBootTest and this whole class would assert against a 404. The endpoint
// is present in the running application; this switches the test environment back to matching it.
@AutoConfigureObservability
@Testcontainers
class MetricsExposureIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @MockitoBean
    private FraudScreeningClient fraudScreeningClient;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @BeforeEach
    void allowScreening() {
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(FraudStatus.ALLOWED, null, null));
    }

    @Test
    void theBusinessMetricsTheDashboardQueriesAreExported() {
        UUID paymentId = paymentService.createPayment(
                UUID.randomUUID(), "metrics-" + UUID.randomUUID(),
                new CreatePaymentRequest(12_345L, "INR", null)).payment().id();
        paymentService.applyTransition(paymentId, PaymentStatus.PENDING_PROVIDER, "metrics test");

        String scrape = scrape();

        // Names and label keys both matter: the dashboard groups by them, and a renamed label is
        // just as invisible as a renamed metric.
        // Not openpay_payments_created_total: "_created" is a reserved OpenMetrics suffix and the
        // client strips it, so a counter named for it silently loses that word on the way out.
        assertThat(scrape).contains("openpay_payments_accepted_total");
        assertThat(scrape).contains("currency=\"INR\"");
        assertThat(scrape).contains("screening=\"ALLOWED\"");
        assertThat(scrape).contains("openpay_payment_transitions_total");
        assertThat(scrape).contains("from=\"CREATED\"");
        assertThat(scrape).contains("to=\"PENDING_PROVIDER\"");
    }

    @Test
    void theOutboxBacklogGaugeIsExported() {
        // The most important number on the dashboard: everything after creation is event-driven, so
        // a stalled relay fails nothing and stops everything.
        assertThat(scrape()).contains("openpay_outbox_unpublished");
    }

    @Test
    void httpTimingsArePublishedAsBucketsNotJustAnAverage() {
        restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        // Spring Boot's default is count, sum, and max — enough for an average, and an average
        // latency hides exactly the tail a customer at a checkout experiences.
        String scrape = scrape();
        assertThat(scrape).contains("http_server_requests_seconds_bucket");
        assertThat(scrape).contains("le=");
    }

    private String scrape() {
        return restTemplate.getForObject(
                "http://localhost:" + port + "/actuator/prometheus", String.class);
    }
}
