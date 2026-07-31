package com.openpay.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchant_webhook_deliveries")
public class WebhookDelivery {

    @Id
    private UUID id;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "target_url", columnDefinition = "text")
    private String targetUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeliveryStatus status;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at")
    private OffsetDateTime nextAttemptAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;

    protected WebhookDelivery() {
        // JPA only
    }

    public WebhookDelivery(UUID merchantId, UUID eventId, String eventType, String payload) {
        this.id = UUID.randomUUID();
        this.merchantId = merchantId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.status = DeliveryStatus.PENDING;
        this.attempts = 0;
        this.createdAt = OffsetDateTime.now();
        // Due immediately; the dispatcher picks it up on its next pass.
        this.nextAttemptAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public String getTargetUrl() {
        return targetUrl;
    }

    public DeliveryStatus getStatus() {
        return status;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public OffsetDateTime getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public OffsetDateTime getDeliveredAt() {
        return deliveredAt;
    }

    public void recordSuccess(String targetUrl, int responseStatus) {
        this.attempts = this.attempts + 1;
        this.targetUrl = targetUrl;
        this.responseStatus = responseStatus;
        this.status = DeliveryStatus.DELIVERED;
        this.deliveredAt = OffsetDateTime.now();
        this.nextAttemptAt = null;
        this.lastError = null;
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * @param backoff how long to wait; ignored once attempts are exhausted
     * @param maxAttempts the point at which we stop trying and leave the row for inspection
     */
    public void recordFailure(String targetUrl, Integer responseStatus, String error,
                              Duration backoff, int maxAttempts) {
        this.attempts = this.attempts + 1;
        this.targetUrl = targetUrl;
        this.responseStatus = responseStatus;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));

        if (this.attempts >= maxAttempts) {
            // Abandoned rather than deleted: a merchant who never got told needs to be findable.
            this.status = DeliveryStatus.ABANDONED;
            this.nextAttemptAt = null;
        } else {
            this.nextAttemptAt = OffsetDateTime.now().plus(backoff);
        }
    }
}
