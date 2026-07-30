package com.openpay.router.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "provider_transactions")
public class ProviderTransaction {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    @Column(name = "attempt_no", nullable = false)
    private Integer attemptNo;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "failure_reason", length = 255)
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ProviderTransaction() {
        // JPA only
    }

    public ProviderTransaction(
            UUID paymentId, UUID merchantId, String providerName, int attemptNo, long amount, String currency) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.providerName = providerName;
        this.attemptNo = attemptNo;
        this.amount = amount;
        this.currency = currency;
        this.status = "DISPATCHING";
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getProviderReference() {
        return providerReference;
    }

    public Integer getAttemptNo() {
        return attemptNo;
    }

    public String getStatus() {
        return status;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void markAccepted(String providerReference) {
        this.providerReference = providerReference;
        this.status = "ACCEPTED";
        this.updatedAt = OffsetDateTime.now();
    }

    public void markFailed(String reason) {
        this.status = "FAILED";
        this.failureReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 255));
        this.updatedAt = OffsetDateTime.now();
    }
}
