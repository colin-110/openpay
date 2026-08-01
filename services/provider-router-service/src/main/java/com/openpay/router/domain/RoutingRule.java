package com.openpay.router.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One statement about where a payment may go.
 *
 * <p>The three narrowing fields — merchant, currency, amount band — are all nullable, and null
 * means "no opinion" rather than "never". A rule with all three null is the general case, which is
 * what a deployment with two acquirers and nothing clever has.
 */
@Entity
@Table(name = "provider_routing_rules")
public class RoutingRule {

    @Id
    private UUID id;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "base_url", nullable = false, length = 500)
    private String baseUrl;

    @Column(nullable = false)
    private Integer priority;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "min_amount")
    private Long minAmount;

    @Column(name = "max_amount")
    private Long maxAmount;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected RoutingRule() {
        // JPA only
    }

    public RoutingRule(
            String providerName,
            String baseUrl,
            int priority,
            UUID merchantId,
            String currency,
            Long minAmount,
            Long maxAmount) {
        this.id = UUID.randomUUID();
        this.providerName = providerName;
        this.baseUrl = baseUrl;
        this.priority = priority;
        this.enabled = true;
        this.merchantId = merchantId;
        this.currency = currency == null ? null : currency.toUpperCase();
        this.minAmount = minAmount;
        this.maxAmount = maxAmount;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    /**
     * Whether this rule has anything to say about a payment.
     *
     * <p>The merchant is checked by the caller, not here: which merchant's rules apply is a
     * decision about the whole set, not about one rule — see
     * {@code RoutingRuleService#candidatesFor}.
     */
    public boolean matches(String paymentCurrency, long amount) {
        if (currency != null && !currency.equalsIgnoreCase(paymentCurrency)) {
            return false;
        }
        // Half-open: [min, max). Adjacent bands written as 0-10000 and 10000-null then cover
        // everything exactly once, with no gap and no overlap at the boundary.
        if (minAmount != null && amount < minAmount) {
            return false;
        }
        return maxAmount == null || amount < maxAmount;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setPriority(int priority) {
        this.priority = priority;
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getProviderName() {
        return providerName;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public Integer getPriority() {
        return priority;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public Long getMinAmount() {
        return minAmount;
    }

    public Long getMaxAmount() {
        return maxAmount;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
