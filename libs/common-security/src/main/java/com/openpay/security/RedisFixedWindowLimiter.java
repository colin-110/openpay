package com.openpay.security;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * A fixed-window counter shared across replicas, because a limiter that only sees its own memory
 * is not a limit at all once there is more than one instance — N replicas each enforcing the same
 * cap means N times the cap.
 *
 * <p>Fixed window rather than a sliding one or a token bucket: it is one INCR and one EXPIRE, which
 * is what makes it correct under concurrent callers without extra coordination. It is not exact —
 * a caller can get up to double the nominal limit across a window boundary — and that imprecision
 * is an acceptable trade for a filter that has to run on every request.
 *
 * <p>Fails open. Losing Redis must not turn into every request failing: a rate limiter is an
 * availability protection, not a security invariant like authentication, and treating a Redis
 * outage as "reject everything" would make the limiter a bigger outage than the abuse it exists to
 * stop.
 */
public class RedisFixedWindowLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisFixedWindowLimiter.class);

    private final StringRedisTemplate redisTemplate;

    public RedisFixedWindowLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * @param key identifies what is being limited, e.g. {@code ratelimit:write:<merchantId>}
     * @return true if this call is within the limit and should proceed
     */
    public boolean tryConsume(String key, int limit, Duration window) {
        // The bucket rotates through a fresh Redis key every window, which is what makes an
        // explicit reset unnecessary: an untouched key simply expires and the next window starts
        // clean.
        long windowIndex = System.currentTimeMillis() / window.toMillis();
        String bucketKey = key + ":" + windowIndex;

        try {
            Long count = redisTemplate.opsForValue().increment(bucketKey);
            if (count != null && count == 1L) {
                // Only the caller that created the bucket sets its expiry, so a burst of concurrent
                // first-callers does not each reissue an EXPIRE for no reason.
                redisTemplate.expire(bucketKey, window.plusSeconds(1));
            }
            return count != null && count <= limit;
        } catch (DataAccessException exception) {
            log.warn("Rate limiter could not reach Redis; allowing the request", exception);
            return true;
        }
    }
}
