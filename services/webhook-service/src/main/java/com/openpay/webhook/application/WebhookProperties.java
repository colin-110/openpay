package com.openpay.webhook.application;

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

    public Map<String, String> getSigningSecrets() {
        return signingSecrets;
    }

    public void setSigningSecrets(Map<String, String> signingSecrets) {
        this.signingSecrets = signingSecrets;
    }
}
