package com.openpay.auth.application;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Throttles repeated failures against a bucket — a wrong API key prefix, a failed login for one
 * email, a failed login from one source — so none of these endpoints can be used to brute-force a
 * credential or enumerate accounts.
 *
 * <p>Backed by Redis rather than a local map. An in-memory counter is only a real limit when there
 * is exactly one instance: with N replicas each enforcing the same cap independently, an attacker
 * gets N times the budget for free, and the count resets every time an instance restarts.
 *
 * <p>The threshold and window are supplied per call rather than fixed at construction, because the
 * same mechanism backs several different budgets with different tolerances — a login can
 * legitimately be retried a handful of times, a shared office IP hitting many different accounts
 * needs a much looser cap, and a wrong API key prefix is not something a real client ever produces
 * more than a couple of times by accident.
 */
@Component
public class ValidationAttemptLimiter {

    private static final Logger log = LoggerFactory.getLogger(ValidationAttemptLimiter.class);
    private static final String KEY_PREFIX = "attempts:";

    private final StringRedisTemplate redisTemplate;

    public ValidationAttemptLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * Called before an attempt is made. Read-only: only {@link #recordFailure} advances the count,
     * so a burst of legitimate successful calls never counts against the budget.
     */
    public void checkAllowed(String bucket, int maxFailures) {
        String count;
        try {
            count = redisTemplate.opsForValue().get(KEY_PREFIX + bucket);
        } catch (DataAccessException exception) {
            // Fails open. This is a supplementary cap on top of the real defence — a constant-time
            // comparison against a credential that is itself hard to guess — not the only thing
            // standing between an attacker and a match. Refusing every login because Redis is
            // briefly unreachable would turn an infrastructure blip into a site-wide login outage,
            // which is a worse outcome than a short window with no throttling.
            log.warn("Attempt limiter could not reach Redis; allowing the request", exception);
            return;
        }
        if (count != null && Long.parseLong(count) >= maxFailures) {
            throw new TooManyAttemptsException("Too many failed authentication attempts. Try again later.");
        }
    }

    public void recordFailure(String bucket, Duration window) {
        try {
            Long count = redisTemplate.opsForValue().increment(KEY_PREFIX + bucket);
            if (count != null && count == 1L) {
                redisTemplate.expire(KEY_PREFIX + bucket, window.plusSeconds(1));
            }
        } catch (DataAccessException exception) {
            log.warn("Attempt limiter could not reach Redis; the failure was not recorded", exception);
        }
    }

    public void recordSuccess(String bucket) {
        try {
            redisTemplate.delete(KEY_PREFIX + bucket);
        } catch (DataAccessException exception) {
            log.warn("Attempt limiter could not reach Redis; a stale failure count may remain", exception);
        }
    }
}
