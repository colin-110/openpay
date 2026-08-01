package com.openpay.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An audit entry as it is returned over HTTP.
 *
 * <p>Shared rather than redefined per service, because the entry is the same shape everywhere and
 * two services returning different JSON for the same table would make a single audit view across
 * the platform harder to build than it needs to be.
 *
 * <p>Serialising the entity directly would work today and break the first time a column is added
 * that should not leave the service.
 */
public record AuditEntryView(
        UUID id,
        AuditAction action,
        String actor,
        String subject,
        UUID merchantId,
        boolean succeeded,
        String detail,
        String sourceIp,
        String correlationId,
        OffsetDateTime occurredAt) {

    public static AuditEntryView of(AuditEntry entry) {
        return new AuditEntryView(
                entry.getId(),
                entry.getAction(),
                entry.getActor(),
                entry.getSubject(),
                entry.getMerchantId(),
                entry.isSucceeded(),
                entry.getDetail(),
                entry.getSourceIp(),
                entry.getCorrelationId(),
                entry.getOccurredAt());
    }
}
