package com.openpay.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.PaymentResponse;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
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

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules(); // Ensure OffsetDateTime works
        paymentService = new PaymentService(paymentRepository, paymentEventRepository, objectMapper);
    }

    @Test
    void createPayment_SavesPaymentAndEvent() {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("100.00"), "USD");

        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey))
                .thenReturn(Optional.empty());

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResponse response = paymentService.createPayment(merchantId, idempotencyKey, request);

        assertNotNull(response.id());
        assertEquals(PaymentStatus.CREATED, response.status());
        assertEquals(new BigDecimal("100.00"), response.amount());

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository).save(eventCaptor.capture());
        
        PaymentEvent event = eventCaptor.getValue();
        assertEquals("PAYMENT_CREATED", event.getType());
        assertEquals(response.id(), event.getPaymentId());
    }

    @Test
    void createPayment_ReturnsExistingOnDuplicate() {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("100.00"), "USD");

        Payment existingPayment = new Payment(UUID.randomUUID(), merchantId, idempotencyKey, new BigDecimal("100.00"), "USD");

        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey))
                .thenReturn(Optional.of(existingPayment));

        PaymentResponse response = paymentService.createPayment(merchantId, idempotencyKey, request);

        assertEquals(existingPayment.getId(), response.id());
    }

    @Test
    void createPayment_HandlesConcurrentDuplicate() {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("100.00"), "USD");

        Payment existingPayment = new Payment(UUID.randomUUID(), merchantId, idempotencyKey, new BigDecimal("100.00"), "USD");

        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey))
                .thenReturn(Optional.empty()) // First check empty
                .thenReturn(Optional.of(existingPayment)); // Second check after exception

        when(paymentRepository.saveAndFlush(any(Payment.class))).thenThrow(new DataIntegrityViolationException("Constraint violation"));

        PaymentResponse response = paymentService.createPayment(merchantId, idempotencyKey, request);

        assertEquals(existingPayment.getId(), response.id());
    }
}
