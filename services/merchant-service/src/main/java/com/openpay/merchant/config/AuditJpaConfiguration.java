package com.openpay.merchant.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/** Brings the shared audit entity and repository into this service's persistence context. */
@Configuration
@ConditionalOnProperty(name = "openpay.audit.enabled", havingValue = "true", matchIfMissing = true)
@EntityScan(basePackages = {"com.openpay.merchant", "com.openpay.audit"})
@EnableJpaRepositories(basePackages = {"com.openpay.merchant", "com.openpay.audit"})
public class AuditJpaConfiguration {
}
