package com.openpay.mockbank.domain;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.bank")
public class BankProperties {

    /** Provider name this instance answers as, e.g. mock-bank-a. */
    private String name = "mock-bank-a";

    /** Where to POST callbacks. Normally webhook-service. */
    private String callbackUrl = "http://localhost:8084/internal/provider/webhooks";

    /** Shared secret used to sign callbacks, so the receiver can prove they came from us. */
    private String signingSecret = "";

    /** Fraction of authorisation attempts that decline, 0.0 to 1.0. */
    private double declineRate = 0.0;

    /** Fraction of attempts that hang past the caller's timeout, simulating a stuck acquirer. */
    private double timeoutRate = 0.0;

    /** Whether this instance refuses everything outright, for exercising failover. */
    private boolean unavailable = false;

    /** Artificial processing delay before responding. */
    private Duration latency = Duration.ofMillis(50);

    /** Delay before the asynchronous callback is sent. */
    private Duration callbackDelay = Duration.ofMillis(300);

    /** How long a simulated hang lasts. Must exceed the router's read timeout to be useful. */
    private Duration hangDuration = Duration.ofSeconds(8);

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCallbackUrl() {
        return callbackUrl;
    }

    public void setCallbackUrl(String callbackUrl) {
        this.callbackUrl = callbackUrl;
    }

    public String getSigningSecret() {
        return signingSecret;
    }

    public void setSigningSecret(String signingSecret) {
        this.signingSecret = signingSecret;
    }

    public double getDeclineRate() {
        return declineRate;
    }

    public void setDeclineRate(double declineRate) {
        this.declineRate = declineRate;
    }

    public double getTimeoutRate() {
        return timeoutRate;
    }

    public void setTimeoutRate(double timeoutRate) {
        this.timeoutRate = timeoutRate;
    }

    public boolean isUnavailable() {
        return unavailable;
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public Duration getLatency() {
        return latency;
    }

    public void setLatency(Duration latency) {
        this.latency = latency;
    }

    public Duration getCallbackDelay() {
        return callbackDelay;
    }

    public void setCallbackDelay(Duration callbackDelay) {
        this.callbackDelay = callbackDelay;
    }

    public Duration getHangDuration() {
        return hangDuration;
    }

    public void setHangDuration(Duration hangDuration) {
        this.hangDuration = hangDuration;
    }
}
