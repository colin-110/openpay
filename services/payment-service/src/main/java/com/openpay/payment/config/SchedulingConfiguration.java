package com.openpay.payment.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables the outbox relay's polling schedule.
 *
 * <p>Switchable so tests that have no broker do not poll a Kafka that is not there.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "openpay.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfiguration {
}
