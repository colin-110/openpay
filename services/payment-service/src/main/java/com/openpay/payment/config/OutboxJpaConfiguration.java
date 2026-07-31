package com.openpay.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Brings the shared outbox entity and repository into this service's persistence context.
 *
 * <p>Deliberately not on the application class. Annotations there are processed by sliced tests
 * too, so {@code @EnableJpaRepositories} would try to wire repositories inside a
 * {@code @WebMvcTest} that has no EntityManagerFactory and fail the entire slice.
 */
@Configuration
@ConditionalOnProperty(name = "openpay.outbox.enabled", havingValue = "true", matchIfMissing = true)
@EntityScan(basePackages = {"com.openpay.payment", "com.openpay.outbox"})
@EnableJpaRepositories(basePackages = {"com.openpay.payment", "com.openpay.outbox"})
public class OutboxJpaConfiguration {
}
