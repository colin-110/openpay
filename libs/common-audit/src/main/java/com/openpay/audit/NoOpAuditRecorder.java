package com.openpay.audit;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discards audit entries.
 *
 * <p>Installed only when {@code openpay.audit.enabled=false}, which exists so a test with no
 * database can still start a service that depends on a recorder. It warns on construction and again
 * on every entry it drops: a service that has quietly stopped keeping an audit trail should be
 * impossible to miss in the logs.
 */
class NoOpAuditRecorder extends AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(NoOpAuditRecorder.class);

    NoOpAuditRecorder() {
        super(null);
        log.warn("Audit recording is DISABLED (openpay.audit.enabled=false). "
                + "Nothing on this instance is being written to the audit log.");
    }

    @Override
    public void record(AuditAction action, String actor, String subject, UUID merchantId, String detail) {
        log.warn("Audit recording disabled; dropping {} by {}", action, actor);
    }

    @Override
    public void recordFailure(AuditAction action, String actor, String subject, UUID merchantId, String detail) {
        log.warn("Audit recording disabled; dropping failed {} by {}", action, actor);
    }
}
