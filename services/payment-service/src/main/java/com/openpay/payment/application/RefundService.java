package com.openpay.payment.application;

import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.RefundCreated;
import com.openpay.outbox.OutboxWriter;
import com.openpay.payment.api.CreateRefundRequest;
import com.openpay.payment.api.PagedResponse;
import com.openpay.payment.api.RefundResponse;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.domain.Refund;
import com.openpay.payment.domain.RefundRepository;
import com.openpay.payment.domain.RefundStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
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
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    private static final String AGGREGATE_TYPE = "refund";

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final OutboxWriter outboxWriter;

    public RefundService(
            RefundRepository refundRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            OutboxWriter outboxWriter) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.outboxWriter = outboxWriter;
    }

    @Transactional
    public RefundResult createRefund(UUID merchantId, String idempotencyKey, CreateRefundRequest request) {
        Payment payment = paymentRepository.findByIdAndMerchantId(request.paymentId(), merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(request.paymentId()));

        // Only captured money can come back. Refunding an authorised payment would return funds
        // that were never actually taken.
        if (payment.getStatus() != PaymentStatus.CAPTURED) {
            throw new RefundNotAllowedException(
                    "Payment " + payment.getId() + " is " + payment.getStatus()
                            + "; only a CAPTURED payment can be refunded");
        }

        long alreadyCommitted = refundRepository.sumCommittedAmount(payment.getId());
        long refundable = payment.getAmount() - alreadyCommitted;
        long amount = request.amount() != null ? request.amount() : refundable;

        String fingerprint = fingerprint(payment.getId(), amount);

        Optional<Refund> existing =
                refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey);
        if (existing.isPresent()) {
            return new RefundResult(replay(existing.get(), idempotencyKey, fingerprint), false);
        }

        if (refundable <= 0) {
            throw new RefundNotAllowedException(
                    "Payment " + payment.getId() + " has already been fully refunded");
        }
        if (amount > refundable) {
            throw new RefundNotAllowedException(
                    "Cannot refund " + amount + "; only " + refundable + " of payment "
                            + payment.getId() + " is still refundable");
        }

        try {
            Refund refund = refundRepository.saveAndFlush(new Refund(
                    UUID.randomUUID(), payment.getId(), merchantId, amount,
                    payment.getCurrency(), request.reason(), idempotencyKey, fingerprint));

            outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.REFUND_CREATED, refund.getId(),
                    new RefundCreated(
                            refund.getId(), payment.getId(), merchantId, amount, payment.getCurrency()));

            log.info("Created refund {} for payment {} ({} of {} refundable)",
                    refund.getId(), payment.getId(), amount, refundable);
            return new RefundResult(toResponse(refund), true);
        } catch (DataIntegrityViolationException exception) {
            log.info("Concurrent refund request for key {}, returning the winner", idempotencyKey);
            Refund winner = refundRepository.findByMerchantIdAndIdempotencyKey(merchantId, idempotencyKey)
                    .orElseThrow(() -> exception);
            return new RefundResult(replay(winner, idempotencyKey, fingerprint), false);
        }
    }

    /**
     * Applies the provider's answer.
     *
     * <p>Tolerant of redelivery for the same reason payment transitions are: Kafka delivers at
     * least once and acquirers re-send, so an outcome we have already applied is success rather
     * than an error.
     */
    @Transactional
    public boolean applyOutcome(UUID refundId, RefundStatus target, String failureReason) {
        Refund refund = refundRepository.findById(refundId).orElse(null);
        if (refund == null) {
            log.warn("Ignoring {} for unknown refund {}", target, refundId);
            return false;
        }
        if (refund.getStatus() == target) {
            log.info("Refund {} is already {}, treating as a duplicate delivery", refundId, target);
            return false;
        }
        if (!refund.getStatus().canTransitionTo(target)) {
            log.warn("Dropping illegal refund transition {} -> {} for {}",
                    refund.getStatus(), target, refundId);
            return false;
        }

        refund.transitionTo(target, failureReason);

        if (target == RefundStatus.SUCCEEDED) {
            outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.REFUND_SUCCEEDED, refund.getId(),
                    new com.openpay.events.payload.RefundSucceeded(
                            refund.getId(), refund.getPaymentId(), refund.getMerchantId(),
                            refund.getAmount(), refund.getCurrency()));
            markPaymentRefundedIfFullyReturned(refund);
        }

        log.info("Refund {} is now {}", refundId, target);
        return true;
    }

    @Transactional(readOnly = true)
    public RefundResponse getRefund(UUID merchantId, UUID refundId) {
        return refundRepository.findByIdAndMerchantId(refundId, merchantId)
                .map(this::toResponse)
                .orElseThrow(() -> new RefundNotFoundException(refundId));
    }

    @Transactional(readOnly = true)
    public List<RefundResponse> refundsForPayment(UUID merchantId, UUID paymentId) {
        paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .orElseThrow(() -> new PaymentNotFoundException(paymentId));
        return refundRepository.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream()
                .map(this::toResponse)
                .toList();
    }

    /** Every refund this merchant has made, newest first, regardless of which payment it was for. */
    @Transactional(readOnly = true)
    public PagedResponse<RefundResponse> listRefunds(UUID merchantId, RefundStatus status, Pageable pageable) {
        Page<RefundResponse> page = (status == null
                        ? refundRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable)
                        : refundRepository.findByMerchantIdAndStatusOrderByCreatedAtDesc(
                                merchantId, status, pageable))
                .map(this::toResponse);
        return new PagedResponse<>(
                page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
    }

    /**
     * The payment is only REFUNDED once every minor unit of it has actually come back.
     *
     * <p>The transition is routed through PaymentService rather than applied to the entity here,
     * so it emits payment.status-updated.v1 like every other transition. Writing the entity
     * directly moved the payment but told nobody, leaving the ledger, settlement, and the
     * merchant's own webhook unaware it had happened.
     */
    private void markPaymentRefundedIfFullyReturned(Refund refund) {
        Payment payment = paymentRepository.findById(refund.getPaymentId()).orElse(null);
        if (payment == null) {
            return;
        }
        long succeeded = refundRepository.findByPaymentIdOrderByCreatedAtAsc(payment.getId()).stream()
                .filter(candidate -> candidate.getStatus() == RefundStatus.SUCCEEDED)
                .mapToLong(Refund::getAmount)
                .sum();

        if (succeeded >= payment.getAmount() && payment.getStatus() == PaymentStatus.CAPTURED) {
            paymentService.applyTransition(
                    payment.getId(), PaymentStatus.REFUNDED,
                    "fully refunded by refund " + refund.getId());
        }
    }

    private RefundResponse replay(Refund stored, String idempotencyKey, String fingerprint) {
        if (stored.getRequestFingerprint() != null
                && !stored.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return toResponse(stored);
    }

    private String fingerprint(UUID paymentId, long amount) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest((paymentId + "|" + amount).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 should always be available", exception);
        }
    }

    private RefundResponse toResponse(Refund refund) {
        return new RefundResponse(
                refund.getId(),
                refund.getPaymentId(),
                refund.getStatus(),
                refund.getAmount(),
                refund.getCurrency(),
                refund.getReason(),
                refund.getFailureReason(),
                refund.getCreatedAt(),
                refund.getUpdatedAt());
    }
}
