package com.openpay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    /** Positive, in minor units. Direction is implied by this being a refund. */
    @Column(nullable = false)
    private Long amount;

    // CHAR(3) in the migration, so Hibernate has to be told it is bpchar. Left as plain varchar,
    // ddl-auto: validate refuses to start the service.
    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(length = 255)
    private String reason;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Refund() {
        // JPA only
    }

    public Refund(
            UUID id, UUID paymentId, UUID merchantId, long amount, String currency,
            String reason, String idempotencyKey, String requestFingerprint) {
        this.id = id;
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.status = RefundStatus.PENDING;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void transitionTo(RefundStatus target, String failureReason) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidRefundTransitionException(status, target);
        }
        this.status = target;
        this.failureReason = failureReason == null
                ? null
                : failureReason.substring(0, Math.min(failureReason.length(), 255));
        this.updatedAt = OffsetDateTime.now();
    }
}
