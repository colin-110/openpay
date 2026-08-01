package com.openpay.payment.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.PaymentCreated;
import com.openpay.events.payload.PaymentStatusUpdated;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.PagedResponse;
import com.openpay.payment.api.PaymentMethodRequest;
import com.openpay.payment.api.PaymentMethodView;
import com.openpay.payment.api.PaymentResponse;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentMethod;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.outbox.OutboxWriter;
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
    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            OutboxWriter outboxWriter,
            ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.outboxWriter = outboxWriter;
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
                    request.currency(),
                    toPaymentMethod(request.paymentMethod())));

            recordEvent(payment, "PAYMENT_CREATED");

            // Same transaction as the payment row: the event cannot escape without the payment,
            // and the payment cannot commit without the event.
            outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.PAYMENT_CREATED, payment.getId(), new PaymentCreated(
                    payment.getId(), payment.getMerchantId(), payment.getAmount(), payment.getCurrency()));

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
    public PagedResponse<PaymentResponse> listPayments(UUID merchantId, PaymentStatus status, Pageable pageable) {
        Page<PaymentResponse> page = (status == null
                        ? paymentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                        : paymentRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                                merchantId, status, pageable))
                .map(this::toResponse);
        return new PagedResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * Applies a lifecycle transition driven by an inbound event.
     *
     * <p>Deliberately tolerant rather than strict. Kafka delivery is at-least-once and acquirers
     * re-send callbacks, so the same outcome arrives more than once. A redelivery asking for the
     * state we are already in is success, not an error, and an out-of-order redelivery is dropped
     * rather than thrown: throwing would put the consumer in a redelivery loop over a message that
     * can never succeed.
     *
     * @return true if the payment actually moved
     */
    @Transactional
    public boolean applyTransition(UUID paymentId, PaymentStatus target, String reason) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            log.warn("Ignoring transition to {} for unknown payment {}", target, paymentId);
            return false;
        }

        PaymentStatus current = payment.getStatus();
        if (current == target) {
            log.info("Payment {} is already {}, treating as a duplicate delivery", paymentId, target);
            return false;
        }
        if (!current.canTransitionTo(target)) {
            log.warn("Dropping illegal transition {} -> {} for payment {} ({})",
                    current, target, paymentId, reason);
            return false;
        }

        payment.transitionTo(target);
        recordEvent(payment, "PAYMENT_" + target.name());
        outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.PAYMENT_STATUS_UPDATED, payment.getId(), new PaymentStatusUpdated(
                payment.getId(),
                payment.getMerchantId(),
                current.name(),
                target.name(),
                payment.getAmount(),
                payment.getCurrency()));

        log.info("Payment {} moved {} -> {} ({})", paymentId, current, target, reason);
        return true;
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

    /** Canonical hash of the fields that define the payment. */
    private String fingerprint(CreatePaymentRequest request) {
        String canonical = request.amount() + "|" + request.currency().toUpperCase();
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

    /**
     * Note what is dropped: the instrument token the merchant sent never reaches the entity. It is
     * what the acquirer is given, not something this service has any reason to keep.
     */
    private PaymentMethod toPaymentMethod(PaymentMethodRequest request) {
        return request == null
                ? null
                : new PaymentMethod(
                        request.type(), request.network(), request.last4(), request.vpa(), request.bank());
    }

    private PaymentResponse toResponse(Payment payment) {
        PaymentMethod method = payment.getPaymentMethod();
        return new PaymentResponse(
                payment.getId(),
                payment.getStatus(),
                payment.getAmount(),
                payment.getCurrency(),
                method == null
                        ? null
                        : new PaymentMethodView(
                                method.getType(),
                                method.getNetwork(),
                                method.getLast4(),
                                method.getVpa(),
                                method.getBank()),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }

    /** Explicit event shape, so the stored event does not drift with the entity's internals. */
    private record PaymentEventPayload(
            UUID paymentId,
            UUID merchantId,
            PaymentStatus status,
            Long amount,
            String currency,
            OffsetDateTime occurredAt) {
    }
}
