package com.openpay.audit;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditRepository extends JpaRepository<AuditEntry, UUID> {

    /**
     * Newest first, optionally narrowed. Both filters are nullable rather than split into four
     * methods, because an operator reading an audit log narrows by whatever they happen to know.
     */
    @Query("""
            SELECT e FROM AuditEntry e
            WHERE (:action IS NULL OR e.action = :action)
              AND (:merchantId IS NULL OR e.merchantId = :merchantId)
            ORDER BY e.occurredAt DESC
            """)
    Page<AuditEntry> search(
            @Param("action") AuditAction action,
            @Param("merchantId") UUID merchantId,
            Pageable pageable);
}
