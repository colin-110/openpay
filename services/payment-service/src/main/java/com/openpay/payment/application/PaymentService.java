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
import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentMethod;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.infrastructure.FraudScreeningClient;
import com.openpay.payment.infrastructure.FraudScreeningClient.ScreeningOutcome;
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
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private static final String AGGREGATE_TYPE = "payment";

    private final PaymentRepository paymentRepository;
    private final PaymentEventRepository paymentEventRepository;
    private final OutboxWriter outboxWriter;
    private final ObjectMapper objectMapper;
    private final FraudScreeningClient fraudScreeningClient;
    private final PaymentMetrics metrics;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(
            PaymentRepository paymentRepository,
            PaymentEventRepository paymentEventRepository,
            OutboxWriter outboxWriter,
            ObjectMapper objectMapper,
            FraudScreeningClient fraudScreeningClient,
            PaymentMetrics metrics,
            TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.paymentEventRepository = paymentEventRepository;
        this.outboxWriter = outboxWriter;
        this.objectMapper = objectMapper;
        this.fraudScreeningClient = fraudScreeningClient;
        this.metrics = metrics;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Deliberately <strong>not</strong> {@code @Transactional}.
     *
     * <p>Screening is a synchronous HTTP call to fraud-service. When this method held the
     * transaction, a pooled database connection was acquired before that call and sat idle for its
     * entire duration — a network round-trip's worth of a resource there are only twenty-five of.
     * By Little's Law that hold time is a direct divisor of write throughput, so paying for the
     * screening latency twice (once in the response, once in connection occupancy) capped the
     * whole service well below what the database could actually take.
     *
     * <p>Nothing about correctness changes. The idempotency replay check is a read, the screening
     * call is external, and the payment row and its outbox row are still written inside one
     * transaction — which is the invariant that actually matters. Two concurrent creations with
     * the same key are still settled by the unique index, exactly as before; that path was always
     * the real guard, because two requests could interleave between the SELECT and the INSERT even
     * when they shared a transaction.
     *
     * <p>The write uses {@link TransactionTemplate} rather than a second {@code @Transactional}
     * method on this class, and that is not a style preference. Spring's transaction support is
     * proxy-based: a call from one method of a bean to another goes straight to the target object
     * and never passes through the proxy, so the annotation would be silently ignored and the
     * payment would commit without its outbox row. Programmatic demarcation has no such trap —
     * the transaction is where the code says it is.
     */
    public PaymentResult createPayment(UUID merchantId, String idempotencyKey, CreatePaymentRequest request) {
        log.info("Processing create payment for merchantId={} idempotencyKey={}", merchantId, idempotencyKey);
        String fingerprint = fingerprint(request);

        Optional<Payment> existing = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            // A replay is answered from what was stored. Screening is not re-run: the first
            // decision is the decision, and fraud-service would return it unchanged anyway.
            return new PaymentResult(replay(existing.get(), idempotencyKey, fingerprint), false);
        }

        // The id is minted here rather than by the database so screening can be keyed on it before
        // anything is written. That is what makes the gate idempotent across a retried creation.
        UUID paymentId = UUID.randomUUID();
        PaymentMethod method = toPaymentMethod(request.paymentMethod());
        ScreeningOutcome screening = fraudScreeningClient.screen(
                paymentId,
                merchantId,
                request.amount(),
                request.currency().toUpperCase(),
                method == null ? null : method.getType());

        if (screening.status() == FraudStatus.BLOCKED) {
            // Nothing is persisted. A refused payment is not a payment that happened, and storing
            // one would put a FAILED row in every merchant's list for traffic they never took.
            // The decision itself is recorded — in fraud-service, which owns it.
            log.info("Refusing payment for merchantId={} on rule '{}'", merchantId, screening.ruleName());
            metrics.paymentBlocked(screening.ruleName());
            throw new PaymentBlockedException(screening.ruleName());
        }

        // The part that actually needs a transaction: the payment row and its outbox row,
        // committed together or not at all. Returns null when a concurrent request won the unique
        // constraint, because that transaction is already doomed and the winner has to be read
        // from a fresh one.
        PaymentResult result = transactionTemplate.execute(status -> {
            try {
                Payment payment = paymentRepository.saveAndFlush(new Payment(
                        paymentId,
                        merchantId,
                        idempotencyKey,
                        fingerprint,
                        request.amount(),
                        request.currency(),
                        method,
                        screening.status()));

                recordEvent(payment, "PAYMENT_CREATED");

                if (payment.isHeld()) {
                    // Deliberately no PAYMENT_CREATED event. Publishing it is what starts routing,
                    // and a payment held for review must not reach an acquirer. The release path is
                    // FraudDecisionListener, driven by fraud.check-completed.v1.
                    log.info("Holding payment id={} for review on rule '{}'", paymentId, screening.ruleName());
                } else {
                    // Same transaction as the payment row: the event cannot escape without the
                    // payment, and the payment cannot commit without the event.
                    outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.PAYMENT_CREATED, payment.getId(),
                            new PaymentCreated(payment.getId(), payment.getMerchantId(),
                                    payment.getAmount(), payment.getCurrency()));
                    log.info("Created payment id={} merchantId={}", payment.getId(), merchantId);
                }

                metrics.paymentCreated(payment.getCurrency(), payment.getFraudStatus());
                return new PaymentResult(toResponse(payment), true);
            } catch (DataIntegrityViolationException exception) {
                // A concurrent request won the unique constraint on (merchant_id, idempotency_key).
                // The insert is already marked rollback-only by the time this is caught, so the
                // winner has to be read after this transaction ends rather than inside it.
                log.info("Concurrent request for idempotencyKey={}, returning the winning payment",
                        idempotencyKey);
                status.setRollbackOnly();
                return null;
            }
        });

        if (result != null) {
            return result;
        }
        Payment winner = paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "Unique constraint fired but no payment found for key " + idempotencyKey));
        return new PaymentResult(replay(winner, idempotencyKey, fingerprint), false);
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

        metrics.transition(current, target);
        log.info("Payment {} moved {} -> {} ({})", paymentId, current, target, reason);
        return true;
    }

    /**
     * Applies a screening outcome that arrived after the payment was created.
     *
     * <p>The only thing that releases a held payment. An {@code ALLOW} publishes the
     * {@code PAYMENT_CREATED} event that creation withheld, so routing starts exactly as it would
     * have; a {@code BLOCK} fails the payment.
     *
     * <p>Tolerant of redelivery, like the other consumers: {@code fraud.check-completed.v1} carries
     * every decision, including the ones creation already acted on, so most deliveries here are
     * about payments that were never held. Those are a no-op rather than an error.
     *
     * @return true if the payment actually moved
     */
    @Transactional
    public boolean applyScreeningOutcome(UUID paymentId, boolean allowed, String reason) {
        Payment payment = paymentRepository.findById(paymentId).orElse(null);
        if (payment == null) {
            // A blocked payment was never persisted, so its completion event refers to nothing.
            // Expected, not an error.
            log.debug("Screening outcome for unknown payment {}, nothing to release", paymentId);
            return false;
        }
        if (!payment.isHeld()) {
            log.debug("Payment {} is {}, not held; ignoring screening outcome",
                    paymentId, payment.getFraudStatus());
            return false;
        }

        payment.resolveScreening(allowed ? FraudStatus.ALLOWED : FraudStatus.BLOCKED);

        if (allowed) {
            recordEvent(payment, "PAYMENT_RELEASED");
            outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.PAYMENT_CREATED, payment.getId(), new PaymentCreated(
                    payment.getId(), payment.getMerchantId(), payment.getAmount(), payment.getCurrency()));
            log.info("Released payment {} after review; routing will now start", paymentId);
        } else {
            // Goes through the same transition path as any other failure, so the ledger and the
            // merchant's webhooks see a refused payment in the shape they already understand.
            PaymentStatus current = payment.getStatus();
            payment.transitionTo(PaymentStatus.FAILED);
            metrics.transition(current, PaymentStatus.FAILED);
            recordEvent(payment, "PAYMENT_FAILED");
            outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.PAYMENT_STATUS_UPDATED, payment.getId(),
                    new PaymentStatusUpdated(
                            payment.getId(),
                            payment.getMerchantId(),
                            current.name(),
                            PaymentStatus.FAILED.name(),
                            payment.getAmount(),
                            payment.getCurrency()));
            log.info("Failed payment {} after review: {}", paymentId, reason);
        }
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
                payment.getFraudStatus(),
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
