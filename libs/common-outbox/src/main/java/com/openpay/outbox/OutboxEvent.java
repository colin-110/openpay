package com.openpay.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

    @Id
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 100)
    private String aggregateId;

    @Column(nullable = false, length = 100)
    private String topic;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "last_error")
    private String lastError;

    protected OutboxEvent() {
        // JPA only
    }

    public OutboxEvent(
            String aggregateType, String aggregateId, String topic, String payload, String correlationId) {
        this.id = UUID.randomUUID();
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.payload = payload;
        this.correlationId = correlationId;
        this.createdAt = OffsetDateTime.now();
        this.attempts = 0;
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public String getPayload() {
        return payload;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public Integer getAttempts() {
        return attempts;
    }

    public void markPublished() {
        this.publishedAt = OffsetDateTime.now();
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts = this.attempts + 1;
        // Truncated: a stack trace in a column is not worth an oversized row.
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 1000));
    }
}
