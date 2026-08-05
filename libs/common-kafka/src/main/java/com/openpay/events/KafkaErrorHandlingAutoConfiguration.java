package com.openpay.events;

import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaConsumerFactoryCustomizer;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
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

    /**
     * Caps how many records a consumer claims per poll, so a backlog drains instead of livelocking.
     *
     * <p>Kafka's default is 500 records per poll against a 5-minute {@code max.poll.interval.ms},
     * and every listener here does real work per record — a database write, a state transition, an
     * outbox append. That is fine at steady state, where a poll returns a handful of records, and
     * it is a trap the moment a backlog exists:
     *
     * <ol>
     *   <li>The consumer claims 500 records.
     *   <li>At ~600ms each — entirely normal on a loaded host — the batch needs five minutes.
     *   <li>It misses {@code max.poll.interval.ms}, so the broker considers it dead and rebalances.
     *   <li>Offsets were never committed, so the replacement claims the <em>same</em> 500 records.
     * </ol>
     *
     * <p>That loop makes no progress, ever, and it does not look like a failure: there is no error
     * and no dead letter, just a consumer group whose lag never moves and whose generation id
     * climbs. Observed here after a load test left ~14,000 callbacks queued — the group rebalanced
     * eight times in twelve minutes and drained nothing, and payments stopped advancing past
     * CREATED with no error anywhere to explain it.
     *
     * <p>This is the failure mode a payment platform can least afford, because a backlog is
     * exactly what an outage leaves behind: the recovery path is the thing that breaks. Fifty
     * records is around thirty seconds of work at that rate — an order of magnitude inside the
     * interval, with room for a host far slower than this one.
     *
     * <p>A customizer rather than a property in nine {@code application.yml} files: the correct
     * value is a property of how these listeners work, not of any one service, and one service
     * quietly missing the setting is how this comes back.
     */
    /**
     * Uses the partitions the broker provides.
     *
     * <p>Topics are created with several partitions (see KAFKA_NUM_PARTITIONS in
     * docker-compose.yml), and a consumer group gets at most one active consumer per partition —
     * so without this a service would take one partition's worth of work no matter how many
     * partitions existed. Set centrally for the same reason as the poll size below: it is a
     * property of how these listeners work rather than of any one service, and one service quietly
     * missing it is how a consumer group ends up with a straggler nobody notices.
     */
    @Bean
    public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
            listenerConcurrency(KafkaErrorHandlingProperties properties) {
        return container -> container.setConcurrency(properties.getListenerConcurrency());
    }

    @Bean
    public DefaultKafkaConsumerFactoryCustomizer boundedPollSize(KafkaErrorHandlingProperties properties) {
        return consumerFactory -> consumerFactory.updateConfigs(
                Map.of(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, properties.getMaxPollRecords()));
    }
}
