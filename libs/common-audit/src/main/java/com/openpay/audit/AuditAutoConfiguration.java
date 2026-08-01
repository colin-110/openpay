package com.openpay.audit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires audit recording into any service that owns an {@code audit_logs} table.
 *
 * <p>The bean is declared here rather than annotated {@code @Component}, because this package sits
 * outside every application's component scan. A service using this library must also add
 * {@code com.openpay.audit} to its {@code @EntityScan} and {@code @EnableJpaRepositories} — the
 * same arrangement as {@code common-outbox}: shared code, a table per service, because each service
 * owns its own schema.
 *
 * <p>{@code openpay.audit.enabled=false} swaps in a recorder that writes nothing, so a sliced test
 * with no {@code EntityManagerFactory} can still start the services that depend on one. It exists
 * for tests, not for deployments, which is why it announces itself at WARN rather than starting
 * quietly — a platform silently keeping no audit trail is exactly the failure this library is for.
 */
@AutoConfiguration
public class AuditAutoConfiguration {

    @Configuration
    @ConditionalOnProperty(name = "openpay.audit.enabled", havingValue = "true", matchIfMissing = true)
    static class RecordingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AuditRecorder auditRecorder(AuditRepository auditRepository) {
            return new AuditRecorder(auditRepository);
        }
    }

    @Configuration
    @ConditionalOnProperty(name = "openpay.audit.enabled", havingValue = "false")
    static class DisabledConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public AuditRecorder auditRecorder() {
            return new NoOpAuditRecorder();
        }
    }
}
