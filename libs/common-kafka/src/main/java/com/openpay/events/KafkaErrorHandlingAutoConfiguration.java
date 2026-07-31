package com.openpay.events;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Routes messages a consumer cannot process to a dead-letter topic.
 *
 * <p>Spring Kafka's default is to retry ten times and then log and move on. In a payment system
 * that is the worst option available: the event is gone, nothing alerts, and a payment simply
 * stops advancing with no trace of why. Sending it to a DLQ keeps the message, its key, and the
 * failure reason, so it can be inspected and replayed once the cause is fixed.
 *
 * <p>Retries are few and quick on purpose. Most failures here are either transient (retrying twice
 * fixes them) or structural (retrying a thousand times will not), and a consumer stuck retrying a
 * poison message is a consumer not processing the payments behind it.
 */
// @ConditionalOnBean only sees beans registered before this class is evaluated, so without
// ordering it silently decides KafkaTemplate does not exist, no handler is created, and Spring's
// default takes over: retry ten times then drop the record. That failure is invisible until you
// send a poison message and find it vanished instead of landing in a DLQ.
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(KafkaErrorHandlingProperties.class)
public class KafkaErrorHandlingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(KafkaTemplate.class)
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public CommonErrorHandler deadLetterErrorHandler(
            KafkaTemplate<String, String> kafkaTemplate, KafkaErrorHandlingProperties properties) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                // Partition -1 lets the broker place the record: the DLQ topic will not
                // necessarily have the same partition count as the source topic.
                (record, exception) -> {
                    String deadLetterTopic = DeadLetterTopics.forTopic(record.topic());
                    log.error("Routing unprocessable record from {} (key={}) to {}",
                            record.topic(), record.key(), deadLetterTopic, exception);
                    return new TopicPartition(deadLetterTopic, -1);
                });

        return new DefaultErrorHandler(
                recoverer,
                new FixedBackOff(properties.getRetryIntervalMs(), properties.getMaxRetries()));
    }
}
