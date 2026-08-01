package com.openpay.events.dlq;

import com.openpay.events.DeadLetterTopics;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;

/**
 * Inspecting and replaying dead letters.
 *
 * <p>Before this existed, a message that landed in a DLQ stayed there until somebody re-published
 * it by hand with a console producer — which meant, in practice, that it stayed there.
 *
 * <p>Three operations, and the separation between them is the point. <strong>Peek</strong> shows
 * what is waiting without committing anything, so looking costs nothing.
 * <strong>Replay</strong> re-publishes to the original topic. <strong>Discard</strong> commits past
 * a message without replaying it, for the ones that will never succeed. Making discard an explicit
 * operation is what stops an operator from using replay as a way to clear a queue and then
 * discovering the poison message has simply come back.
 *
 * <p>Nothing here runs on a schedule. Automatic replay is how a poison message becomes an infinite
 * loop, and the decision to try again belongs to a person who knows what was fixed.
 */
public class DeadLetterAdmin {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterAdmin.class);

    /**
     * A fixed group, so successive calls resume where the last one stopped rather than re-reading
     * everything. Distinct from every service's own group: joining a consumer's group would take
     * partitions away from the consumer that is trying to keep working.
     */
    private static final String REPLAY_GROUP = "openpay-dlq-replay";

    private final ConsumerFactory<String, String> consumerFactory;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final DeadLetterProperties properties;

    public DeadLetterAdmin(
            ConsumerFactory<String, String> consumerFactory,
            KafkaTemplate<String, String> kafkaTemplate,
            DeadLetterProperties properties) {
        this.consumerFactory = consumerFactory;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    /** The source topics this service will act on. */
    public List<String> knownTopics() {
        return List.copyOf(properties.getTopics());
    }

    /** What is waiting, without consuming it. Safe to call as often as you like. */
    public List<DeadLetterRecord> peek(String sourceTopic, int limit) {
        String deadLetterTopic = deadLetterTopicFor(sourceTopic);
        try (Consumer<String, String> consumer = createConsumer()) {
            consumer.subscribe(List.of(deadLetterTopic));
            List<ConsumerRecord<String, String>> records = drain(consumer, bounded(limit));
            // No commit, and the assignment is dropped when the consumer closes, so the next call
            // sees exactly the same messages.
            return records.stream().map(record -> describe(deadLetterTopic, record)).toList();
        }
    }

    /**
     * Re-publishes dead letters to the topic they failed on.
     *
     * <p>Publish first, commit after. A crash between the two replays a message twice rather than
     * losing it — the right way round, because every consumer here is already idempotent for
     * exactly this reason, while a lost message is gone.
     *
     * @return how many were re-published
     */
    public int replay(String sourceTopic, int limit) {
        String deadLetterTopic = deadLetterTopicFor(sourceTopic);
        try (Consumer<String, String> consumer = createConsumer()) {
            consumer.subscribe(List.of(deadLetterTopic));
            List<ConsumerRecord<String, String>> records = drain(consumer, bounded(limit));
            if (records.isEmpty()) {
                return 0;
            }

            for (ConsumerRecord<String, String> record : records) {
                String target = originalTopicOf(record, sourceTopic);
                kafkaTemplate.send(target, record.key(), record.value());
                log.info("Replayed dead letter from {} (key={}, offset={}) to {}",
                        deadLetterTopic, record.key(), record.offset(), target);
            }
            // One flush for the batch, so a broker that refuses the publish fails before anything
            // is committed and the messages stay in the DLQ.
            kafkaTemplate.flush();
            consumer.commitSync();

            log.warn("Replayed {} dead letters from {}", records.size(), deadLetterTopic);
            return records.size();
        }
    }

    /**
     * Gives up on messages: commits past them without re-publishing.
     *
     * <p>Deliberately separate from replay, and deliberately logged at WARN with the keys. Throwing
     * a payment event away is a decision somebody should be able to find afterwards.
     *
     * @return how many were discarded
     */
    public int discard(String sourceTopic, int limit) {
        String deadLetterTopic = deadLetterTopicFor(sourceTopic);
        try (Consumer<String, String> consumer = createConsumer()) {
            consumer.subscribe(List.of(deadLetterTopic));
            List<ConsumerRecord<String, String>> records = drain(consumer, bounded(limit));
            if (records.isEmpty()) {
                return 0;
            }
            consumer.commitSync();
            log.warn("Discarded {} dead letters from {} without replaying them. Keys: {}",
                    records.size(), deadLetterTopic,
                    records.stream().map(ConsumerRecord::key).toList());
            return records.size();
        }
    }

    /**
     * Refuses a topic this service was not configured for.
     *
     * <p>An allowlist rather than whatever the caller sends. Replay publishes to a topic derived
     * from the request, and without this the endpoint would let anyone holding the operator token
     * inject a message into any topic on the platform.
     */
    private String deadLetterTopicFor(String sourceTopic) {
        if (!properties.getTopics().contains(sourceTopic)) {
            throw new UnknownDeadLetterTopicException(sourceTopic, properties.getTopics());
        }
        return DeadLetterTopics.forTopic(sourceTopic);
    }

    /**
     * Where to send a replayed message.
     *
     * <p>Prefers the header Spring Kafka wrote when it gave up, and falls back to the topic the
     * caller named. They agree in every ordinary case; the header wins because it is what actually
     * failed, and a message that somehow reached the wrong DLQ should go back where it came from.
     */
    private String originalTopicOf(ConsumerRecord<String, String> record, String fallback) {
        String fromHeader = headerAsString(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        return fromHeader == null || fromHeader.isBlank() ? fallback : fromHeader;
    }

    private List<ConsumerRecord<String, String>> drain(Consumer<String, String> consumer, int limit) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        // Two polls minimum: the first usually returns nothing because it is still being assigned
        // partitions, and reporting an empty DLQ when it is not empty is the worst answer this
        // tool could give.
        for (int attempt = 0; attempt < 2 && collected.size() < limit; attempt++) {
            ConsumerRecords<String, String> polled = consumer.poll(properties.getPollTimeout());
            for (ConsumerRecord<String, String> record : polled) {
                collected.add(record);
                if (collected.size() >= limit) {
                    break;
                }
            }
        }
        return collected;
    }

    private DeadLetterRecord describe(String deadLetterTopic, ConsumerRecord<String, String> record) {
        return new DeadLetterRecord(
                deadLetterTopic,
                originalTopicOf(record, null),
                record.partition(),
                record.offset(),
                record.key(),
                record.value(),
                headerAsString(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                headerAsString(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                OffsetDateTime.ofInstant(Instant.ofEpochMilli(record.timestamp()), ZoneOffset.UTC));
    }

    private String headerAsString(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private int bounded(int requested) {
        return Math.max(1, Math.min(requested, properties.getMaxBatch()));
    }

    private Consumer<String, String> createConsumer() {
        Properties overrides = new Properties();
        overrides.put(ConsumerConfig.GROUP_ID_CONFIG, REPLAY_GROUP);
        // Manual commits only. Auto-commit would mark messages consumed on poll, so a peek would
        // quietly destroy the queue it was asked to display.
        overrides.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        overrides.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        overrides.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(properties.getMaxBatch()));
        return consumerFactory.createConsumer(REPLAY_GROUP, null, null, overrides);
    }
}
