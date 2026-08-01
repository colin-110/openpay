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
@Table(name = "fraud_rules")
public class FraudRule {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "rule_type", nullable = false, length = 40)
    private RuleType ruleType;

    @Column(nullable = false)
    private Long threshold;

    @Column(name = "window_seconds")
    private Integer windowSeconds;

    @Column(length = 3, columnDefinition = "bpchar")
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RuleAction action;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected FraudRule() {
        // JPA only
    }

    public FraudRule(
            String name,
            RuleType ruleType,
            long threshold,
            Integer windowSeconds,
            String currency,
            RuleAction action,
            int priority) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.ruleType = ruleType;
        this.threshold = threshold;
        this.windowSeconds = windowSeconds;
        this.currency = currency == null ? null : currency.toUpperCase();
        this.action = action;
        this.priority = priority;
        this.enabled = true;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Whether this rule has anything to say about a payment in this currency.
     *
     * <p>A rule with no currency applies to all of them. That is right for the velocity rules, whose
     * threshold is a count, and wrong for {@link RuleType#AMOUNT_OVER}, whose threshold is minor
     * units — 5,000,000 paise and 5,000,000 cents are not the same policy. The API refuses to create
     * that combination rather than silently comparing across currencies.
     */
    public boolean appliesTo(String paymentCurrency) {
        return currency == null || currency.equalsIgnoreCase(paymentCurrency);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public RuleType getRuleType() {
        return ruleType;
    }

    public Long getThreshold() {
        return threshold;
    }

    public Integer getWindowSeconds() {
        return windowSeconds;
    }

    public String getCurrency() {
        return currency;
    }

    public RuleAction getAction() {
        return action;
    }

    public Integer getPriority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
