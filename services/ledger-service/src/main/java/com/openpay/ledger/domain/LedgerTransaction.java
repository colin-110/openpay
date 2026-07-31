package com.openpay.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ledger_transactions")
public class LedgerTransaction {

    @Id
    private UUID id;

    /** The event that caused this posting. Unique, which is what makes replay safe. */
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Column(nullable = false, length = 3, columnDefinition = "bpchar")
    private String currency;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected LedgerTransaction() {
        // JPA only
    }

    public LedgerTransaction(
            UUID eventId, String referenceType, UUID referenceId, String currency, String description) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.currency = currency;
        this.description = description;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public String getCurrency() {
        return currency;
    }

    public String getDescription() {
        return description;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
