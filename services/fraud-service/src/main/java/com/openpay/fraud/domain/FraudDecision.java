package com.openpay.fraud.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "fraud_decisions")
public class FraudDecision {

    private static final int MAX_REASON_LENGTH = 500;

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "payment_method_type", length = 20)
    private String paymentMethodType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DecisionOutcome outcome;

    @Column(name = "rule_name", length = 100)
    private String ruleName;

    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolved_outcome", length = 10)
    private DecisionOutcome resolvedOutcome;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected FraudDecision() {
        // JPA only
    }

    public FraudDecision(
            UUID paymentId,
            UUID merchantId,
            long amount,
            String currency,
            String paymentMethodType,
            DecisionOutcome outcome,
            String ruleName,
            String reason) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.merchantId = merchantId;
        this.amount = amount;
        this.currency = currency == null ? null : currency.toUpperCase();
        this.paymentMethodType = paymentMethodType;
        this.outcome = outcome;
        this.ruleName = ruleName;
        this.reason = truncate(reason);
        this.createdAt = OffsetDateTime.now();
    }

    /**
     * Closes a review.
     *
     * @throws IllegalStateException if the decision was never a review, or has already been closed.
     *     Both are refused rather than tolerated: a second resolution would publish a second
     *     release event for a payment that has already moved on.
     */
    public void resolve(DecisionOutcome finalOutcome, String operator) {
        if (outcome != DecisionOutcome.REVIEW) {
            throw new IllegalStateException(
                    "Decision for payment " + paymentId + " is " + outcome + ", not a review");
        }
        if (resolvedOutcome != null) {
            throw new IllegalStateException(
                    "Review for payment " + paymentId + " was already resolved as " + resolvedOutcome);
        }
        if (!finalOutcome.isFinal()) {
            throw new IllegalArgumentException("A review must be resolved to ALLOW or BLOCK");
        }
        this.resolvedOutcome = finalOutcome;
        this.resolvedBy = operator;
        this.resolvedAt = OffsetDateTime.now();
    }

    /** The outcome that counts: the operator's if there was one, otherwise the rule engine's. */
    public DecisionOutcome effectiveOutcome() {
        return resolvedOutcome != null ? resolvedOutcome : outcome;
    }

    public boolean isOpenReview() {
        return outcome == DecisionOutcome.REVIEW && resolvedOutcome == null;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_REASON_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_REASON_LENGTH);
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

    public String getPaymentMethodType() {
        return paymentMethodType;
    }

    public DecisionOutcome getOutcome() {
        return outcome;
    }

    public String getRuleName() {
        return ruleName;
    }

    public String getReason() {
        return reason;
    }

    public DecisionOutcome getResolvedOutcome() {
        return resolvedOutcome;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
