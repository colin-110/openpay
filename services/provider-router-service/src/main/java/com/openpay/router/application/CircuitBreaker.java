package com.openpay.router.application;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-provider circuit breaker.
 *
 * <p>Hand-rolled rather than pulled from a library, because the interesting behaviour is small and
 * worth being able to read: after {@code failureThreshold} consecutive failures the breaker opens
 * and the provider is skipped entirely, sparing every subsequent payment the timeout it would
 * otherwise wait out. After {@code openDuration} one probe is allowed through; if it succeeds the
 * breaker closes, if it fails the provider is shut out for another window.
 */
public class CircuitBreaker {

    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> openedAt = new AtomicReference<>();

    public CircuitBreaker(String name, int failureThreshold, Duration openDuration) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public String name() {
        return name;
    }

    public State state() {
        Instant opened = openedAt.get();
        if (opened == null) {
            return State.CLOSED;
        }
        return opened.plus(openDuration).isBefore(Instant.now()) ? State.HALF_OPEN : State.OPEN;
    }

    /** False only while the breaker is fully open. HALF_OPEN lets a single probe through. */
    public boolean allowsRequest() {
        return state() != State.OPEN;
    }

    public void recordSuccess() {
        consecutiveFailures.set(0);
        openedAt.set(null);
    }

    public void recordFailure() {
        if (consecutiveFailures.incrementAndGet() >= failureThreshold) {
            openedAt.set(Instant.now());
        }
    }

    public int consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
