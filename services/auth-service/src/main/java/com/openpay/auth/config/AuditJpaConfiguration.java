package com.openpay.auth.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Brings the shared audit entity and repository into this service's persistence context.
 *
 * <p>Same arrangement as {@code common-outbox} elsewhere: the code is shared, the table is not.
 */
@Configuration
@ConditionalOnProperty(name = "openpay.audit.enabled", havingValue = "true", matchIfMissing = true)
@EntityScan(basePackages = {"com.openpay.auth", "com.openpay.audit"})
@EnableJpaRepositories(basePackages = {"com.openpay.auth", "com.openpay.audit"})
public class AuditJpaConfiguration {
}
