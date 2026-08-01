package com.openpay.events.dlq;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.dlq")
public class DeadLetterProperties {

    /**
     * Whether this service exposes the replay tool. Off unless a service asks for it: only the
     * services that actually consume something have dead letters to replay, and an idle endpoint
     * on the rest is one more thing to secure for no benefit.
     */
    private boolean enabled = false;

    /**
     * The source topics this service consumes, named without the {@code .dlq} infix. The tool
     * derives dead-letter names from these, so an operator asks about {@code payment.created.v1}
     * rather than having to know the naming convention.
     *
     * <p>An allowlist rather than a free-text topic parameter. A replay endpoint that will publish
     * to any topic it is handed is a way to inject arbitrary events into the platform using nothing
     * but the operator token.
     */
    private List<String> topics = new ArrayList<>();

    /** Ceiling on how many messages one call will touch, whatever the caller asks for. */
    private int maxBatch = 100;

    /** How long a peek or replay waits for records before concluding the topic is empty. */
    private Duration pollTimeout = Duration.ofSeconds(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public int getMaxBatch() {
        return maxBatch;
    }

    public void setMaxBatch(int maxBatch) {
        this.maxBatch = maxBatch;
    }

    public Duration getPollTimeout() {
        return pollTimeout;
    }

    public void setPollTimeout(Duration pollTimeout) {
        this.pollTimeout = pollTimeout;
    }
}
