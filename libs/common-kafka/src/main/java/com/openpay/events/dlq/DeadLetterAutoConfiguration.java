package com.openpay.events.dlq;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the dead-letter replay tool into a service that asks for it.
 *
 * <p>Off unless {@code openpay.dlq.enabled} is true: only the services that actually consume
 * something have dead letters, and an unused endpoint on the rest is one more thing to secure for
 * no benefit. A service that turns it on must also put {@code /internal/dlq} behind its ops token.
 */
// After KafkaAutoConfiguration for the same reason the error handler is: @ConditionalOnBean only
// sees what has already been registered, and evaluating this first would silently decide there is
// no ConsumerFactory and register nothing.
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@ConditionalOnProperty(name = "openpay.dlq.enabled", havingValue = "true")
@EnableConfigurationProperties(DeadLetterProperties.class)
public class DeadLetterAutoConfiguration {

    @Bean
    @ConditionalOnBean({ConsumerFactory.class, KafkaTemplate.class})
    @ConditionalOnMissingBean
    public DeadLetterAdmin deadLetterAdmin(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterProperties properties) {
        return new DeadLetterAdmin(consumerFactory, kafkaTemplate, properties);
    }

    @Bean
    @ConditionalOnBean(DeadLetterAdmin.class)
    @ConditionalOnMissingBean
    public DeadLetterController deadLetterController(DeadLetterAdmin admin) {
        return new DeadLetterController(admin);
    }
}
