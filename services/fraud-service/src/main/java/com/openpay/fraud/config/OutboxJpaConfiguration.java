package com.openpay.fraud.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Brings the shared outbox entity and repository into this service's persistence context.
 *
 * <p>Deliberately not on the application class, so a sliced test that has no EntityManagerFactory
 * is not forced to wire JPA repositories it will never use.
 */
@Configuration
@ConditionalOnProperty(name = "openpay.outbox.enabled", havingValue = "true", matchIfMissing = true)
@EntityScan(basePackages = {"com.openpay.fraud", "com.openpay.outbox"})
@EnableJpaRepositories(basePackages = {"com.openpay.fraud", "com.openpay.outbox"})
public class OutboxJpaConfiguration {
}
