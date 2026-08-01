package com.openpay.merchant.api;

import com.openpay.audit.AuditAction;
import com.openpay.audit.AuditEntryView;
import com.openpay.audit.AuditRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading this service's audit log, on the operator tier.
 *
 * <p>Read-only by design. Entries are written by the code doing the thing being recorded; an
 * endpoint that accepted them would let anyone holding the token manufacture history.
 */
@RestController
@ConditionalOnProperty(name = "openpay.audit.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/internal/audit")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditRepository auditRepository;

    public AuditController(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    @GetMapping
    public List<AuditEntryView> search(
            @RequestParam(name = "action", required = false) AuditAction action,
            @RequestParam(name = "merchantId", required = false) UUID merchantId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return auditRepository
                .search(action, merchantId,
                        PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)))
                .map(AuditEntryView::of)
                .getContent();
    }
}
