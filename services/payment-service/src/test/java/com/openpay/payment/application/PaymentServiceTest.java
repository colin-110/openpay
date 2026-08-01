package com.openpay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.outbox.OutboxWriter;
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

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        paymentService = new PaymentService(
                paymentRepository, paymentEventRepository, outboxWriter, objectMapper);
    }

    @Test
    void createsPaymentAndRecordsEvent() {
        UUID merchantId = UUID.randomUUID();
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

    /** Builds a stored payment carrying the fingerprint the service would have written. */
    private Payment existingPayment(UUID merchantId, long minorUnits, String currency) {
        Payment payment = new Payment(
                UUID.randomUUID(), merchantId, IDEMPOTENCY_KEY, null, minorUnits, currency, null);
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
