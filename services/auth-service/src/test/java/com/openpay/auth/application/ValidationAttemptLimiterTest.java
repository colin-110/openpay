package com.openpay.auth.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class ValidationAttemptLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final ValidationAttemptLimiter limiter = new ValidationAttemptLimiter(redisTemplate);

    @Test
    void allowsAnAttemptWithNoRecordedFailures() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn(null);

        assertThatCode(() -> limiter.checkAllowed("login:someone@example.test", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsAnAttemptBelowTheThreshold() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("9");

        assertThatCode(() -> limiter.checkAllowed("login:someone@example.test", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesOnceTheThresholdIsReached() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("10");

        assertThatThrownBy(() -> limiter.checkAllowed("login:someone@example.test", 10))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    void appliesADifferentThresholdToADifferentBucket() {
        // The same mechanism backs budgets with very different tolerances — one account versus one
        // source address — so the threshold belongs to the caller, not to the limiter.
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("20");

        assertThatThrownBy(() -> limiter.checkAllowed("login:someone@example.test", 10))
                .isInstanceOf(TooManyAttemptsException.class);
        assertThatCode(() -> limiter.checkAllowed("login-src:203.0.113.10", 50))
                .doesNotThrowAnyException();
    }

    @Test
    void setsAnExpiryOnlyForTheFirstFailureInAWindow() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        limiter.recordFailure("login:someone@example.test", Duration.ofMinutes(15));

        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void doesNotExtendTheWindowOnEveryLaterFailure() {
        // Re-expiring on each failure would let a slow trickle of attempts hold a bucket open
        // forever, so the window would never actually reset.
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(4L);

        limiter.recordFailure("login:someone@example.test", Duration.ofMinutes(15));

        verify(redisTemplate, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void clearingABucketRemovesItsCount() {
        limiter.recordSuccess("login:someone@example.test");

        verify(redisTemplate).delete("attempts:login:someone@example.test");
    }

    /**
     * Fails open. This is a cap on top of the real defence — a constant-time comparison against a
     * credential that is itself hard to guess — not the only thing between an attacker and a
     * match. Refusing every login because Redis blipped would be a worse outage than a brief
     * window with no throttling.
     */
    @Test
    void allowsTheAttemptWhenRedisIsUnreachable() {
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("redis is down"));

        assertThatCode(() -> limiter.checkAllowed("login:someone@example.test", 10))
                .doesNotThrowAnyException();
    }

    @Test
    void recordingAFailureSurvivesRedisBeingUnreachable() {
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("redis is down"));

        assertThatCode(() -> limiter.recordFailure("login:someone@example.test", Duration.ofMinutes(15)))
                .doesNotThrowAnyException();
    }
}
