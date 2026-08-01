package com.openpay.audit;

import com.openpay.observability.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes audit entries.
 *
 * <p>Two decisions in here are the whole point of the class.
 *
 * <p><strong>Its own transaction.</strong> {@code REQUIRES_NEW}, so an entry survives the rollback
 * of whatever it was recording. Without it the most valuable entries — the refused login, the
 * rejected key issuance — would be written and then thrown away with the failing transaction, and
 * the log would contain only the actions that worked.
 *
 * <p><strong>It never breaks the thing it records.</strong> A failed insert is logged at ERROR and
 * swallowed. The alternative turns an audit-table outage into a platform outage: nobody can sign in
 * because the record of them signing in could not be written. The exposure is that someone who can
 * already break writes to this table can act unlogged — but that requires database access, at which
 * point the audit log was never the control holding them back.
 */
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditRepository auditRepository;

    public AuditRecorder(AuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    // Both entry points carry the annotation rather than delegating to one that has it. A call
    // from inside this class would go straight to the target and never reach the proxy, so the
    // new transaction would silently not happen — which is the one thing this class must get right.

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, String actor, String subject, UUID merchantId, String detail) {
        persist(action, actor, subject, merchantId, true, detail);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AuditAction action, String actor, String subject, UUID merchantId, String detail) {
        persist(action, actor, subject, merchantId, false, detail);
    }

    private void persist(
            AuditAction action,
            String actor,
            String subject,
            UUID merchantId,
            boolean succeeded,
            String detail) {
        try {
            auditRepository.save(new AuditEntry(
                    action,
                    actor == null || actor.isBlank() ? "anonymous" : actor,
                    subject,
                    merchantId,
                    succeeded,
                    detail,
                    currentSourceIp(),
                    MDC.get(CorrelationIdFilter.MDC_KEY)));
        } catch (RuntimeException exception) {
            log.error("Could not write audit entry {} for actor {}", action, actor, exception);
        }
    }

    /**
     * Whatever address this service sees. Deliberately not {@code X-Forwarded-For}: that header is
     * set by the client unless a proxy is configured to overwrite it, and an audit log that records
     * an attacker's chosen value as fact is worse than one that records the proxy.
     */
    private String currentSourceIp() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            // Not in a request: a scheduled job, or a consumer handling an event.
            return null;
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return request.getRemoteAddr();
    }
}
