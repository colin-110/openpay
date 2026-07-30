package com.openpay.router.application;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.router")
public class RouterProperties {

    private List<Provider> providers = new ArrayList<>();

    /** Consecutive failures before a provider is taken out of rotation. */
    private int failureThreshold = 3;

    /** How long a provider stays out before a probe is allowed through. */
    private Duration breakerOpenDuration = Duration.ofSeconds(30);

    private Duration connectTimeout = Duration.ofSeconds(2);

    /** Must be shorter than the acquirer's simulated hang, or a hang is indistinguishable from slow. */
    private Duration readTimeout = Duration.ofSeconds(3);

    public List<Provider> getProviders() {
        return providers;
    }

    public void setProviders(List<Provider> providers) {
        this.providers = providers;
    }

    public int getFailureThreshold() {
        return failureThreshold;
    }

    public void setFailureThreshold(int failureThreshold) {
        this.failureThreshold = failureThreshold;
    }

    public Duration getBreakerOpenDuration() {
        return breakerOpenDuration;
    }

    public void setBreakerOpenDuration(Duration breakerOpenDuration) {
        this.breakerOpenDuration = breakerOpenDuration;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public static class Provider {

        private String name;

        private String baseUrl;

        /** Lower is tried first. */
        private int priority = 100;

        private boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
