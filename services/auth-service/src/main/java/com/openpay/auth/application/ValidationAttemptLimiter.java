package com.openpay.auth.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Throttles repeated failed key validations so the endpoint cannot be used to brute-force keys.
 *
 * <p>Deliberately in-memory and per-instance: it is a cheap first line of defence, not a
 * distributed quota. A multi-instance deployment should move this to Redis.
 */
@Component
public class ValidationAttemptLimiter {

    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;

    public ValidationAttemptLimiter(
            @Value("${openpay.auth.max-failed-validations:20}") int maxFailures,
            @Value("${openpay.auth.failed-validation-window:PT1M}") Duration window) {
        this.maxFailures = maxFailures;
        this.window = window;
    }

    public void checkAllowed(String bucket) {
        Window current = windows.get(bucket);
        if (current == null || current.isExpired(window)) {
            return;
        }
        if (current.failures.get() >= maxFailures) {
            throw new TooManyAttemptsException("Too many failed authentication attempts. Try again later.");
        }
    }

    public void recordFailure(String bucket) {
        windows.compute(bucket, (key, existing) -> {
            if (existing == null || existing.isExpired(window)) {
                return new Window(Instant.now(), new AtomicInteger(1));
            }
            existing.failures.incrementAndGet();
            return existing;
        });
    }

    public void recordSuccess(String bucket) {
        windows.remove(bucket);
    }

    private record Window(Instant startedAt, AtomicInteger failures) {

        boolean isExpired(Duration window) {
            return startedAt.plus(window).isBefore(Instant.now());
        }
    }
}
