package com.openpay.notification.application;

import java.time.Duration;

/**
 * Exponential backoff between delivery attempts.
 *
 * <p>Capped, because unbounded doubling quickly produces retries scheduled beyond any useful
 * horizon, and a merchant whose endpoint came back an hour ago should not wait a week to hear
 * about it.
 */
public class BackoffPolicy {

    private final Duration initial;
    private final Duration max;

    public BackoffPolicy(Duration initial, Duration max) {
        this.initial = initial;
        this.max = max;
    }

    /** @param attemptsSoFar attempts already made, including the one that just failed */
    public Duration backoffAfter(int attemptsSoFar) {
        if (attemptsSoFar <= 1) {
            return initial;
        }
        // Shift rather than Math.pow: doubling an integer cannot drift, and the guard stops a
        // large attempt count from overflowing into a negative delay.
        int doublings = Math.min(attemptsSoFar - 1, 32);
        long millis = initial.toMillis() << doublings;
        if (millis <= 0 || millis > max.toMillis()) {
            return max;
        }
        return Duration.ofMillis(millis);
    }
}
