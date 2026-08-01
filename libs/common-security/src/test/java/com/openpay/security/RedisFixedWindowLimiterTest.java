package com.openpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisFixedWindowLimiterTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);

    private final RedisFixedWindowLimiter limiter = new RedisFixedWindowLimiter(redisTemplate);

    private void stubCount(long count) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(count);
    }

    @Test
    void allowsCallsAtOrBelowTheLimit() {
        stubCount(1);
        assertThat(limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10))).isTrue();

        stubCount(5);
        assertThat(limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void refusesTheCallThatCrossesTheLimit() {
        stubCount(6);
        assertThat(limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10))).isFalse();
    }

    @Test
    void setsAnExpiryOnlyForTheFirstCallerInAWindow() {
        stubCount(1);
        limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10));
        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void doesNotReissueTheExpiryForLaterCallersInTheSameWindow() {
        stubCount(2);
        limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10));
        verify(redisTemplate, org.mockito.Mockito.never()).expire(anyString(), any(Duration.class));
    }

    /**
     * The point of the whole class. Losing Redis must not turn into every request failing — a rate
     * limiter is an availability protection, not a security invariant, and refusing everything
     * because the counter is unreachable would make the limiter worse than the abuse it exists to
     * stop.
     */
    @Test
    void allowsTheRequestWhenRedisIsUnreachable() {
        when(redisTemplate.opsForValue()).thenThrow(new QueryTimeoutException("redis is down"));

        assertThat(limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10))).isTrue();
    }

    @Test
    void differentKeysAreIndependent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment("merchant-a" + windowSuffix())).thenReturn(6L);
        when(valueOperations.increment("merchant-b" + windowSuffix())).thenReturn(1L);

        assertThat(limiter.tryConsume("merchant-a", 5, Duration.ofSeconds(10))).isFalse();
        assertThat(limiter.tryConsume("merchant-b", 5, Duration.ofSeconds(10))).isTrue();
    }

    private String windowSuffix() {
        return ":" + (System.currentTimeMillis() / Duration.ofSeconds(10).toMillis());
    }
}
