package com.openpay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    @Id
    private UUID id;

    @Column(name = "transaction_id", nullable = false)
    private UUID transactionId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EntryDirection direction;

    /** Always positive, in minor units. Direction carries the sign. */
    @Column(nullable = false)
    private Long amount;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LedgerEntry() {
        // JPA only
    }

    public LedgerEntry(UUID transactionId, UUID accountId, EntryDirection direction, long amount, String currency) {
        if (amount <= 0) {
            throw new IllegalArgumentException("Entry amount must be positive; direction carries the sign");
        }
        this.id = UUID.randomUUID();
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.direction = direction;
        this.amount = amount;
        this.currency = currency;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public EntryDirection getDirection() {
        return direction;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
