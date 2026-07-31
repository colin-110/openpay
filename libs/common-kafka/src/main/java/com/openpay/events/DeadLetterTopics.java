package com.openpay.events;

/** Maps a topic to its dead-letter counterpart. */
public final class DeadLetterTopics {

    private static final String VERSION_SUFFIX = ".v1";
    private static final String DLQ = ".dlq";

    /**
     * {@code payment.created.v1} becomes {@code payment.created.dlq.v1}.
     *
     * <p>The version stays on the end so a DLQ message keeps the schema of the topic it came from;
     * a replay tool reading {@code payment.created.dlq.v1} knows exactly what it is holding.
     */
    public static String forTopic(String topic) {
        if (topic.endsWith(VERSION_SUFFIX)) {
            return topic.substring(0, topic.length() - VERSION_SUFFIX.length()) + DLQ + VERSION_SUFFIX;
        }
        return topic + DLQ;
    }

    private DeadLetterTopics() {
    }
}
