package com.openpay.mockbank.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.openpay.mockbank.callback.CallbackSender;
import com.openpay.mockbank.domain.BankProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@link BankController} previously had zero behavioural coverage — only a config-binding smoke
 * test existed anywhere in this service. Decline and timeout are driven by an unseeded
 * {@code ThreadLocalRandom.nextDouble()} that {@code roll() < rate} compares against, which is not
 * mockable, so these tests lean on the edges of that comparison instead: a rate of {@code 0.0}
 * never fires (every roll is {@code >= 0}), a rate of {@code 1.0} always fires (every roll is
 * {@code < 1}). That gives deterministic coverage of both branches without touching the RNG.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BankControllerTest {

    @Mock
    private CallbackSender callbackSender;

    private BankProperties properties;
    private BankController controller;

    @BeforeEach
    void setUp() {
        properties = new BankProperties();
        properties.setName("mock-bank-a");
        properties.setLatency(Duration.ZERO);
        properties.setHangDuration(Duration.ofMillis(30));
        controller = new BankController(properties, callbackSender);
    }

    @Test
    void refusesAPaymentWhenConfiguredUnavailable() throws InterruptedException {
        properties.setUnavailable(true);
        ProviderPaymentRequest request = paymentRequest();

        ResponseEntity<?> response = controller.acceptPayment(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isEqualTo(Map.of("error", "acquirer_unavailable", "provider", "mock-bank-a"));
        // An unavailable acquirer must never schedule an outcome for a payment it refused.
        verify(callbackSender, never()).scheduleOutcome(any(), anyString(), anyBoolean());
    }

    @Test
    void acceptsAPaymentAndSchedulesASuccessfulOutcomeWhenDeclineRateIsZero() throws InterruptedException {
        properties.setDeclineRate(0.0);
        ProviderPaymentRequest request = paymentRequest();

        ResponseEntity<?> response = controller.acceptPayment(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        ProviderPaymentResponse body = (ProviderPaymentResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.providerName()).isEqualTo("mock-bank-a");
        assertThat(body.status()).isEqualTo("ACCEPTED");
        verify(callbackSender).scheduleOutcome(eq(request.paymentId()), anyString(), eq(false));
    }

    @Test
    void acceptsAPaymentButSchedulesADeclineWhenDeclineRateIsOne() throws InterruptedException {
        properties.setDeclineRate(1.0);
        ProviderPaymentRequest request = paymentRequest();

        ResponseEntity<?> response = controller.acceptPayment(request);

        // The acquirer still ACKs the request itself — declining happens on the async callback,
        // never as a synchronous rejection of acceptance.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(callbackSender).scheduleOutcome(eq(request.paymentId()), anyString(), eq(true));
    }

    @Test
    void hangsForTheConfiguredDurationWhenTimeoutRateIsOne() throws InterruptedException {
        properties.setTimeoutRate(1.0);
        ProviderPaymentRequest request = paymentRequest();

        long start = System.nanoTime();
        ResponseEntity<?> response = controller.acceptPayment(request);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        // A stuck acquirer still eventually answers in this simulator — real ones sometimes don't,
        // which is exactly what should trip a caller's own timeout rather than this test's.
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(properties.getHangDuration().toMillis());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void refusesARefundWhenConfiguredUnavailable() throws InterruptedException {
        properties.setUnavailable(true);
        ProviderRefundRequest request = refundRequest();

        ResponseEntity<?> response = controller.acceptRefund(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        verify(callbackSender, never()).scheduleRefundOutcome(any(), any(), anyString(), anyBoolean());
    }

    @Test
    void acceptsARefundAndSchedulesSuccessWhenDeclineRateIsZero() throws InterruptedException {
        properties.setDeclineRate(0.0);
        ProviderRefundRequest request = refundRequest();

        ResponseEntity<?> response = controller.acceptRefund(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(callbackSender).scheduleRefundOutcome(
                eq(request.refundId()), eq(request.paymentId()), eq(request.providerReference()), eq(false));
    }

    @Test
    void acceptsARefundButSchedulesRejectionWhenDeclineRateIsOne() throws InterruptedException {
        properties.setDeclineRate(1.0);
        ProviderRefundRequest request = refundRequest();

        controller.acceptRefund(request);

        verify(callbackSender).scheduleRefundOutcome(
                eq(request.refundId()), eq(request.paymentId()), eq(request.providerReference()), eq(true));
    }

    @Test
    void healthReportsTheConfiguredRatesRatherThanHardcodedDefaults() {
        properties.setDeclineRate(0.25);
        properties.setTimeoutRate(0.1);

        Map<String, Object> health = controller.health();

        assertThat(health)
                .containsEntry("provider", "mock-bank-a")
                .containsEntry("unavailable", false)
                .containsEntry("declineRate", 0.25)
                .containsEntry("timeoutRate", 0.1);
    }

    private ProviderPaymentRequest paymentRequest() {
        return new ProviderPaymentRequest(UUID.randomUUID(), 10_000L, "INR", "merchant-ref-1");
    }

    private ProviderRefundRequest refundRequest() {
        return new ProviderRefundRequest(UUID.randomUUID(), UUID.randomUUID(), 5_000L, "INR", "provider-ref-1");
    }
}
