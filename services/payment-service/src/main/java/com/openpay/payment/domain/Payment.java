package com.openpay.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    /**
     * Hash of the request body this key was first used with. Replaying the same idempotency key with
     * a different body is a client bug, not a retry, and must be rejected rather than silently
     * answered with the original payment.
     */
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Payment() {
        // JPA only
    }

    public Payment(
            UUID id,
            UUID merchantId,
            String idempotencyKey,
            String requestFingerprint,
            BigDecimal amount,
            String currency) {
        this.id = id;
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.amount = amount;
        this.currency = currency;
        this.status = PaymentStatus.CREATED;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public Integer getVersion() {
        return version;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * Moves the payment to {@code newStatus}, refusing transitions the state machine does not allow.
     *
     * @throws InvalidPaymentTransitionException if the move is not legal from the current status
     */
    public void transitionTo(PaymentStatus newStatus) {
        if (!status.canTransitionTo(newStatus)) {
            throw new InvalidPaymentTransitionException(status, newStatus);
        }
        this.status = newStatus;
        this.updatedAt = OffsetDateTime.now();
    }
}
