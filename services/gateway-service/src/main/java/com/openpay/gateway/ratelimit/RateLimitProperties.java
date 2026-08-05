package com.openpay.gateway.ratelimit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openpay.rate-limit")
public class RateLimitProperties {

    /** Off switch for local debugging. On everywhere else. */
    private boolean enabled = true;

    /** Mutating requests a single merchant may make in one window. */
    private int limit = 30;

    private Duration window = Duration.ofSeconds(5);

    /**
     * Tokenisation attempts one <em>caller</em> may make in {@link #publishableWindow}.
     *
     * <p>Sized against a person, not a server. A customer typing a card in makes one attempt, or
     * three if they fat-finger the expiry; twenty in a minute is not a shopper. The per-merchant
     * limit above stays in force as well, so this only ever narrows what one visitor can do.
     */
    private int publishableLimit = 20;

    /**
     * A minute rather than the five seconds used above, because the two limits are defending
     * against different things. The merchant window smooths burst load. This one has to catch a
     * script working steadily through a list of stolen card numbers, and a five-second window would
     * let it run all day at four a second without ever tripping.
     */
    private Duration publishableWindow = Duration.ofSeconds(60);

    /**
     * Whether {@code X-Forwarded-For} can be believed.
     *
     * <p>Off by default and deliberately so: the header is client-supplied, and trusting it when
     * nothing is overwriting it would let any caller reset their own bucket by changing one value.
     * Turn it on only when a proxy that sets it — Caddy, an ALB, an ingress — is the sole route in.
     */
    private boolean trustForwardedFor = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }

    public int getPublishableLimit() {
        return publishableLimit;
    }

    public void setPublishableLimit(int publishableLimit) {
        this.publishableLimit = publishableLimit;
    }

    public Duration getPublishableWindow() {
        return publishableWindow;
    }

    public void setPublishableWindow(Duration publishableWindow) {
        this.publishableWindow = publishableWindow;
    }

    public boolean isTrustForwardedFor() {
        return trustForwardedFor;
    }

    public void setTrustForwardedFor(boolean trustForwardedFor) {
        this.trustForwardedFor = trustForwardedFor;
    }
}
