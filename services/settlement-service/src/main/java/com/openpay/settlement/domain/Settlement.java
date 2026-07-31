package com.openpay.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlements")
public class Settlement {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "settlement_date", nullable = false)
    private LocalDate settlementDate;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Column(name = "item_count", nullable = false)
    private Integer itemCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Settlement() {
        // JPA only
    }

    public Settlement(
            UUID merchantId, String currency, LocalDate settlementDate,
            long grossAmount, long feeAmount, long netAmount, int itemCount) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.currency = currency;
        this.settlementDate = settlementDate;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.itemCount = itemCount;
        this.status = SettlementStatus.CREATED;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public String getCurrency() {
        return currency;
    }

    public LocalDate getSettlementDate() {
        return settlementDate;
    }

    public Long getGrossAmount() {
        return grossAmount;
    }

    public Long getFeeAmount() {
        return feeAmount;
    }

    public Long getNetAmount() {
        return netAmount;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public SettlementStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void markCompleted() {
        if (this.status == SettlementStatus.COMPLETED) {
            throw new IllegalStateException("Settlement " + id + " is already completed");
        }
        this.status = SettlementStatus.COMPLETED;
        this.updatedAt = OffsetDateTime.now();
    }
}
