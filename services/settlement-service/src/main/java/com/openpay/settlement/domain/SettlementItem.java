package com.openpay.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "settlement_items")
public class SettlementItem {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "gross_amount", nullable = false)
    private Long grossAmount;

    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    @Column(name = "net_amount", nullable = false)
    private Long netAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementItemStatus status;

    @Column(name = "settlement_id")
    private UUID settlementId;

    @Column(name = "captured_at", nullable = false)
    private OffsetDateTime capturedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected SettlementItem() {
        // JPA only
    }

    public SettlementItem(
            UUID merchantId, UUID paymentId, String currency,
            long grossAmount, long feeAmount, long netAmount, OffsetDateTime capturedAt) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.paymentId = paymentId;
        this.currency = currency;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.netAmount = netAmount;
        this.status = SettlementItemStatus.PENDING;
        this.capturedAt = capturedAt;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getCurrency() {
        return currency;
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

    public SettlementItemStatus getStatus() {
        return status;
    }

    public UUID getSettlementId() {
        return settlementId;
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public void assignTo(UUID settlementId) {
        if (this.status == SettlementItemStatus.SETTLED) {
            throw new IllegalStateException(
                    "Item " + id + " is already in settlement " + this.settlementId);
        }
        this.settlementId = settlementId;
        this.status = SettlementItemStatus.SETTLED;
    }
}
