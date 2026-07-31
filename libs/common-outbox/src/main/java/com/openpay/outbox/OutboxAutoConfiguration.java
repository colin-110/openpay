package com.openpay.outbox;

import com.openpay.events.EventCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the transactional outbox into any service that owns an {@code outbox_events} table.
 *
 * <p>The beans are declared here rather than annotated {@code @Component}, because this package
 * sits outside every application's component scan and the annotation would quietly find nothing.
 * For the same reason a service using this library must add {@code com.openpay.outbox} to its
 * {@code @EntityScan} and {@code @EnableJpaRepositories}.
 *
 * <p>The writer is always available; only the relay's schedule is switchable. A test that asserts
 * an outbox row was written still needs the writer, it just does not want a background thread
 * polling a broker that is not running.
 */
@AutoConfiguration
@ConditionalOnProperty(name = "openpay.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OutboxWriter outboxWriter(OutboxRepository outboxRepository, EventCodec eventCodec) {
        return new OutboxWriter(outboxRepository, eventCodec);
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "openpay.outbox.relay-enabled", havingValue = "true", matchIfMissing = true)
    static class RelayConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public OutboxRelay outboxRelay(
                OutboxRepository outboxRepository,
                KafkaTemplate<String, String> kafkaTemplate,
                @Value("${openpay.outbox.batch-size:100}") int batchSize,
                @Value("${openpay.outbox.send-timeout-seconds:10}") long sendTimeoutSeconds) {
            return new OutboxRelay(outboxRepository, kafkaTemplate, batchSize, sendTimeoutSeconds);
        }

        @Bean
        @ConditionalOnMissingBean
        public OutboxRetentionJob outboxRetentionJob(
                OutboxRepository outboxRepository,
                @Value("${openpay.outbox.retention:P7D}") java.time.Duration retention) {
            return new OutboxRetentionJob(outboxRepository, retention);
        }
    }
}
