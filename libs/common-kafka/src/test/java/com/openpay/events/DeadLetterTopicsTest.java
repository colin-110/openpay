package com.openpay.events;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DeadLetterTopicsTest {

    @Test
    void keepsTheVersionSuffixOnTheEnd() {
        // A replay tool reading the DLQ needs to know which schema it is holding.
        assertThat(DeadLetterTopics.forTopic("payment.created.v1")).isEqualTo("payment.created.dlq.v1");
        assertThat(DeadLetterTopics.forTopic(OpenPayTopics.PROVIDER_CALLBACK_RECEIVED))
                .isEqualTo("provider.callback-received.dlq.v1");
    }

    @Test
    void handlesAnUnversionedTopic() {
        assertThat(DeadLetterTopics.forTopic("legacy-topic")).isEqualTo("legacy-topic.dlq");
    }
}
