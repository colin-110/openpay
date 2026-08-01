package com.openpay.webhook.application;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.webhook")
public class WebhookProperties {

    /**
     * Signing secret per provider name. A callback from a provider with no configured secret is
     * refused: accepting unsigned money movement would let anyone mark a payment captured.
     */
    private Map<String, String> signingSecrets = new HashMap<>();

    /**
     * How far a callback's timestamp may be from ours before it is refused. Five minutes is
     * generous enough to absorb ordinary clock drift and a slow retry, and short enough that a
     * captured message is worthless almost immediately.
     */
    private Duration tolerance = Duration.ofMinutes(5);

    public Duration getTolerance() {
        return tolerance;
    }

    public void setTolerance(Duration tolerance) {
        this.tolerance = tolerance;
    }

    public Map<String, String> getSigningSecrets() {
        return signingSecrets;
    }

    public void setSigningSecrets(Map<String, String> signingSecrets) {
        this.signingSecrets = signingSecrets;
    }
}
