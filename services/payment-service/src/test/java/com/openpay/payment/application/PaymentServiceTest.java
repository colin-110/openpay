package com.openpay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.events.OpenPayTopics;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.infrastructure.FraudScreeningClient;
import com.openpay.payment.infrastructure.FraudScreeningClient.ScreeningOutcome;
import com.openpay.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String IDEMPOTENCY_KEY = "key-123";
    private static final long USD_100 = 10_000L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private FraudScreeningClient fraudScreeningClient;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        paymentService = new PaymentService(
                paymentRepository, paymentEventRepository, outboxWriter, objectMapper, fraudScreeningClient,
                new PaymentMetrics(new SimpleMeterRegistry()));
    }

    @Test
    void createsPaymentAndRecordsEvent() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.ALLOWED);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isTrue();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(result.payment().amount()).isEqualTo(USD_100);

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("PAYMENT_CREATED");
        assertThat(eventCaptor.getValue().getPaymentId()).isEqualTo(result.payment().id());
    }

    @Test
    void aBlockedPaymentIsNeverPersisted() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(FraudStatus.BLOCKED, "extreme-value-payment", "too big"));

        assertThatThrownBy(() ->
                paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD")))
                .isInstanceOf(PaymentBlockedException.class);

        // A refused payment is not a payment that happened. Storing a FAILED row would put traffic
        // the merchant never took into their list.
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void aHeldPaymentIsStoredButNotAnnouncedForRouting() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.HELD);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.payment().fraudStatus()).isEqualTo(FraudStatus.HELD);
        // Publishing PAYMENT_CREATED is what starts routing. A held payment must not reach an
        // acquirer, so the event is withheld until a review releases it.
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void aPaymentThatCouldNotBeScreenedSaysSo() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.UNSCREENED);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        // It goes through — but "we decided this was fine" and "nobody looked" must not read the
        // same afterwards.
        assertThat(result.payment().fraudStatus()).isEqualTo(FraudStatus.UNSCREENED);
        verify(outboxWriter).append(any(), eq(OpenPayTopics.PAYMENT_CREATED), any(), any());
    }

    @Test
    void releasingAHeldPaymentAnnouncesTheRoutingItWithheld() {
        Payment held = heldPayment();
        when(paymentRepository.findById(held.getId())).thenReturn(Optional.of(held));

        assertThat(paymentService.applyScreeningOutcome(held.getId(), true, null)).isTrue();

        assertThat(held.getFraudStatus()).isEqualTo(FraudStatus.ALLOWED);
        assertThat(held.getStatus()).isEqualTo(PaymentStatus.CREATED);
        verify(outboxWriter).append(any(), eq(OpenPayTopics.PAYMENT_CREATED), any(), any());
    }

    @Test
    void blockingAHeldPaymentFailsIt() {
        Payment held = heldPayment();
        when(paymentRepository.findById(held.getId())).thenReturn(Optional.of(held));

        assertThat(paymentService.applyScreeningOutcome(held.getId(), false, "manual block")).isTrue();

        assertThat(held.getFraudStatus()).isEqualTo(FraudStatus.BLOCKED);
        assertThat(held.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(outboxWriter).append(any(), eq(OpenPayTopics.PAYMENT_STATUS_UPDATED), any(), any());
    }

    @Test
    void aScreeningOutcomeForAPaymentThatWasNeverHeldChangesNothing() {
        Payment allowed = new Payment(
                UUID.randomUUID(), UUID.randomUUID(), IDEMPOTENCY_KEY, null, USD_100, "USD",
                null, FraudStatus.ALLOWED);
        when(paymentRepository.findById(allowed.getId())).thenReturn(Optional.of(allowed));

        // fraud.check-completed.v1 carries every decision, including the ones creation already
        // acted on. Re-publishing PAYMENT_CREATED for those would route each payment twice.
        assertThat(paymentService.applyScreeningOutcome(allowed.getId(), true, null)).isFalse();
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void replayWithSameBodyReturnsOriginalAndReportsNotCreated() {
        UUID merchantId = UUID.randomUUID();
        Payment existing = existingPayment(merchantId, USD_100, "USD");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isFalse();
        assertThat(result.payment().id()).isEqualTo(existing.getId());
    }

    @Test
    void replayWithDifferentAmountIsRejected() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existingPayment(merchantId, USD_100, "USD")));

        assertThatThrownBy(() ->
                paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(99_999_900L, "USD")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void replayWithDifferentCurrencyIsRejected() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existingPayment(merchantId, USD_100, "USD")));

        assertThatThrownBy(() ->
                paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "EUR")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void concurrentDuplicateReturnsTheWinningPayment() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.ALLOWED);
        Payment winner = existingPayment(merchantId, USD_100, "USD");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isFalse();
        assertThat(result.payment().id()).isEqualTo(winner.getId());
    }

    @Test
    void getPaymentIsScopedToTheOwningMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(merchantId, paymentId))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    private CreatePaymentRequest request(long minorUnits, String currency) {
        return new CreatePaymentRequest(minorUnits, currency, null);
    }

    private void screeningReturns(FraudStatus status) {
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(status, null, null));
    }

    private Payment heldPayment() {
        return new Payment(
                UUID.randomUUID(), UUID.randomUUID(), IDEMPOTENCY_KEY, null, USD_100, "USD",
                null, FraudStatus.HELD);
    }

    /** Builds a stored payment carrying the fingerprint the service would have written. */
    private Payment existingPayment(UUID merchantId, long minorUnits, String currency) {
        Payment payment = new Payment(
                UUID.randomUUID(), merchantId, IDEMPOTENCY_KEY, null, minorUnits, currency,
                null, FraudStatus.ALLOWED);
        try {
            var method = PaymentService.class.getDeclaredMethod("fingerprint", CreatePaymentRequest.class);
            method.setAccessible(true);
            String fingerprint = (String) method.invoke(paymentService, request(minorUnits, currency));
            var field = Payment.class.getDeclaredField("requestFingerprint");
            field.setAccessible(true);
            field.set(payment, fingerprint);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return payment;
    }
}
