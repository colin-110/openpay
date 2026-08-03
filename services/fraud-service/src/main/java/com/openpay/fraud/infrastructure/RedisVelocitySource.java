package com.openpay.fraud.infrastructure;

import com.openpay.fraud.application.VelocitySource;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Counts recent traffic with Redis sorted sets instead of {@code SELECT COUNT(*)}.
 *
 * <p>The SQL version was correct and indexed, and it still had the worst possible scaling shape for
 * a fraud check: its cost grows with the number of decisions in the window, so the busier a
 * merchant becomes the more expensive it gets to decide whether they are too busy. Two of those
 * counts ran on every single payment. A sorted set keyed by merchant answers the same question by
 * dropping everything older than the window and asking how many members are left — work
 * proportional to the window, not to history, and it stays flat as the table grows.
 *
 * <p><strong>Falls back rather than fails.</strong> If Redis is unreachable this delegates to the
 * database implementation, so an outage costs latency instead of correctness. That is the opposite
 * choice from the rate limiter, which fails open and lets traffic through — here the fallback is
 * exact, so there is no reason to accept a weaker answer.
 *
 * <p><strong>One honest caveat.</strong> Redis holds only what it has seen. If it is flushed or
 * restarted, counts restart from zero and a merchant gets at most one window's worth of
 * under-counting before the set refills. For a sixty-second velocity rule that is sixty seconds of
 * leniency after an infrastructure event, which is a real weakening of the control and is accepted
 * deliberately: the alternative is paying a growing table scan on every payment forever.
 */
public class RedisVelocitySource implements VelocitySource {

    private static final Logger log = LoggerFactory.getLogger(RedisVelocitySource.class);

    private final StringRedisTemplate redis;
    private final VelocitySource fallback;

    public RedisVelocitySource(StringRedisTemplate redis, VelocitySource fallback) {
        this.redis = redis;
        this.fallback = fallback;
    }

    @Override
    public long countForMerchant(UUID merchantId, Duration window) {
        return count("fraud:vel:m:" + merchantId, window,
                () -> fallback.countForMerchant(merchantId, window));
    }

    @Override
    public long countForMerchantAndAmount(UUID merchantId, long amount, Duration window) {
        return count("fraud:vel:a:" + merchantId + ':' + amount, window,
                () -> fallback.countForMerchantAndAmount(merchantId, amount, window));
    }

    /**
     * Records this payment against both counters.
     *
     * <p>Called after the decision is made, never before: the rules ask "how many came before this
     * one", and counting the payment currently being screened would make every threshold fire one
     * payment early.
     */
    public void record(UUID merchantId, long amount, UUID paymentId, Duration retention) {
        try {
            long now = System.currentTimeMillis();
            String member = paymentId.toString();
            add("fraud:vel:m:" + merchantId, member, now, retention);
            add("fraud:vel:a:" + merchantId + ':' + amount, member, now, retention);
        } catch (DataAccessException exception) {
            // The decision is already stored in Postgres, which is the record that matters. A miss
            // here only means this payment is invisible to the next few velocity checks.
            log.warn("Could not record velocity for merchant {} in Redis", merchantId, exception);
        }
    }

    private void add(String key, String member, long nowMillis, Duration retention) {
        redis.opsForZSet().add(key, member, nowMillis);
        // Self-cleaning: a merchant who stops sending traffic stops costing memory, without a
        // sweeper job that would have to know every key that exists.
        redis.expire(key, retention);
    }

    private long count(String key, Duration window, java.util.function.LongSupplier fallbackCount) {
        try {
            long cutoff = System.currentTimeMillis() - window.toMillis();
            // Trim first so the set cannot grow without bound for a merchant that never goes quiet,
            // then count what survived. Both are O(log n) in the size of the window, not of history.
            redis.opsForZSet().removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff);
            Long count = redis.opsForZSet().count(key, cutoff, Double.POSITIVE_INFINITY);
            return count == null ? 0L : count;
        } catch (DataAccessException exception) {
            log.warn("Redis unavailable for velocity on {}, counting from the database instead", key, exception);
            return fallbackCount.getAsLong();
        }
    }
}
