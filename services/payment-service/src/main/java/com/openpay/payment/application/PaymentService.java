package com.openpay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.PagedResponse;
import com.openpay.payment.api.PaymentResponse;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final ObjectMapper objectMapper;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public PaymentResult createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        log.info("Processing create payment for merchantId={} idempotencyKey={}", merchantId, idempotencyKey);
        String fingerprint = fingerprint(request);

        Optional<Payment> existing = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return new PaymentResult(replay(existing.get(), idempotencyKey, fingerprint), false);
        }

        try {
            Payment payment = paymentRepository.saveAndFlush(new Payment(
                    UUID.randomUUID(),
                    merchantId,
                    idempotencyKey,
                    fingerprint,
                    request.amount(),
                    request.currency()));

            recordEvent(payment, "PAYMENT_CREATED");

            log.info("Created payment id={} merchantId={}", payment.getId(), merchantId);
            return new PaymentResult(toResponse(payment), true);
        } catch (DataIntegrityViolationException exception) {
            // A concurrent request won the unique constraint on (merchant_id, idempotency_key).
            log.info("Concurrent request for idempotencyKey={}, returning the winning payment", idempotencyKey);
            Payment winner = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Unique constraint fired but no payment found for key " + idempotencyKey));
            return new PaymentResult(replay(winner, idempotencyKey, fingerprint), false);
        }
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID merchantId, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .map(this::toResponse)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
    }

    @Transactional(readOnly = true)
    public PagedResponse<PaymentResponse> listPayments(UUID merchantId, Pageable pageable) {
        Page<PaymentResponse> page = paymentRepository
                .findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                .map(this::toResponse);
        return new PagedResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Applies a state-machine transition. The entity refuses illegal moves; the {@code @Version}
     * column makes a concurrent transition on the same payment fail rather than silently interleave.
     */
    @Transactional
    public PaymentResponse transition(UUID merchantId, UUID paymentId, PaymentStatus target) {
        Payment payment = paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));

        payment.transitionTo(target);
        recordEvent(payment, "PAYMENT_" + target.name());

        log.info("Payment id={} transitioned to {}", paymentId, target);
        return toResponse(payment);
    }

    private PaymentResponse replay(Payment stored, String idempotencyKey, String fingerprint) {
        if (stored.getRequestFingerprint() != null && !stored.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        log.info("Idempotent replay of payment id={}", stored.getId());
        return toResponse(stored);
    }

    private void recordEvent(Payment payment, String type) {
        paymentEventRepository.save(new PaymentEvent(
                UUID.randomUUID(),
                payment.getId(),
                type,
                serialize(new PaymentEventPayload(
                        payment.getId(),
                        payment.getMerchantId(),
                        payment.getStatus(),
                        payment.getAmount(),
                        payment.getCurrency(),
                        payment.getUpdatedAt()))));
    }

    /**
     * Canonical hash of the fields that define the payment. {@code stripTrailingZeros} keeps
     * {@code 100} and {@code 100.00} from looking like different requests.
     */
    private String fingerprint(CreatePaymentRequest request) {
        String canonical = request.amount().stripTrailingZeros().toPlainString()
                + "|" + request.currency().toUpperCase();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 should always be available", exception);
        }
    }

    private String serialize(PaymentEventPayload payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize payment event payload", exception);
        }
    }

    private PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    /** Explicit event shape, so the stored event does not drift with the entity's internals. */
    private record PaymentEventPayload(
            UUID paymentId,
            UUID merchantId,
            PaymentStatus status,
            BigDecimal amount,
            String currency,
            OffsetDateTime occurredAt) {
    }
}
