package com.openpay.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One thing that happened, and enough about it to answer "who did this" months later.
 *
 * <p>There is no setter and no update path. An audit entry that can be edited is not evidence, and
 * the table grants no UPDATE or DELETE for the same reason.
 */
@Entity
@Table(name = "audit_logs")
public class AuditEntry {

    private static final int MAX_DETAIL_LENGTH = 500;

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AuditAction action;

    /**
     * Who did it, in whatever terms the caller was known by: an email for a human, a token tier for
     * an operator, "anonymous" for an unauthenticated attempt. Not a foreign key — the actor may be
     * someone this service has no row for, and an audit log that can only describe actors it
     * already knows about is missing exactly the entries worth having.
     */
    @Column(name = "actor", nullable = false, length = 200)
    private String actor;

    /** What it was done to: a merchant id, a user id, a key id. */
    @Column(name = "subject", length = 200)
    private String subject;

    /** The merchant the action concerned, where there is one. Nullable: not everything has one. */
    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(nullable = false)
    private boolean succeeded;

    /** A sentence, not a payload. */
    @Column(length = 500)
    private String detail;

    /**
     * The caller's address as this service saw it. Behind a proxy that is the proxy's address
     * unless the deployment is configured to forward the original, which is a deployment decision
     * and not something to guess at from a header an attacker controls.
     */
    @Column(name = "source_ip", length = 45)
    private String sourceIp;

    /** Ties the entry to every log line from the same request. */
    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private OffsetDateTime occurredAt;

    protected AuditEntry() {
        // JPA only
    }

    public AuditEntry(
            AuditAction action,
            String actor,
            String subject,
            UUID merchantId,
            boolean succeeded,
            String detail,
            String sourceIp,
            String correlationId) {
        this.id = UUID.randomUUID();
        this.action = action;
        this.actor = actor;
        this.subject = subject;
        this.merchantId = merchantId;
        this.succeeded = succeeded;
        this.detail = truncate(detail);
        this.sourceIp = sourceIp;
        this.correlationId = correlationId;
        this.occurredAt = OffsetDateTime.now();
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_DETAIL_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_DETAIL_LENGTH);
    }

    public UUID getId() {
        return id;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getMerchantId() {
        return merchantId;
    }

    public boolean isSucceeded() {
        return succeeded;
    }

    public String getDetail() {
        return detail;
    }

    public String getSourceIp() {
        return sourceIp;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }
}
