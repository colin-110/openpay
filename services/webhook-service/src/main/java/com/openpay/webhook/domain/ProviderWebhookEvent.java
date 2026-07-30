package com.openpay.webhook.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "provider_webhook_events")
public class ProviderWebhookEvent {

    @Id
    private UUID id;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "provider_event_id", nullable = false, length = 120)
    private String providerEventId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "provider_reference", length = 120)
    private String providerReference;

    @Column(length = 30)
    private String outcome;

    @Column(name = "signature_verified", nullable = false)
    private boolean signatureVerified;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    protected ProviderWebhookEvent() {
        // JPA only
    }

    public ProviderWebhookEvent(
            String providerName,
            String providerEventId,
            UUID paymentId,
            String providerReference,
            String outcome,
            boolean signatureVerified,
            String payload) {
        this.id = UUID.randomUUID();
        this.providerName = providerName;
        this.providerEventId = providerEventId;
        this.paymentId = paymentId;
        this.providerReference = providerReference;
        this.outcome = outcome;
        this.signatureVerified = signatureVerified;
        this.payload = payload;
        this.receivedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getOutcome() {
        return outcome;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now();
    }
}
