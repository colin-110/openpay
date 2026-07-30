package com.openpay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.PaymentResponse;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository, PaymentEventRepository paymentEventRepository, ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResponse createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        log.info("Processing create payment for merchantId={} idempotencyKey={}", merchantId, idempotencyKey);

        Optional<Payment> existingPayment = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existingPayment.isPresent()) {
            log.info("Found existing payment for idempotencyKey={}", idempotencyKey);
            return toResponse(existingPayment.get());
        }

        try {
            Payment payment = new Payment(UUID.randomUUID(), merchantId, idempotencyKey, request.amount(), request.currency());
            payment = paymentRepository.saveAndFlush(payment);

            PaymentEvent event = new PaymentEvent(UUID.randomUUID(), payment.getId(), "PAYMENT_CREATED", serializePayload(payment));
            paymentEventRepository.save(event);

            log.info("Successfully created payment id={}", payment.getId());
            return toResponse(payment);

        } catch (DataIntegrityViolationException e) {
            // Concurrent request with same idempotency key
            log.info("Concurrent request detected for idempotencyKey={}, fetching existing payment", idempotencyKey);
            Payment payment = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Payment should exist but could not be retrieved"));
            return toResponse(payment);
        }
    }

    private String serializePayload(Payment payment) {
        try {
            return objectMapper.writeValueAsString(payment);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize payment event payload", e);
        }
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCreatedAt()
        );
    }
}
