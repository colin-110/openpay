package com.openpay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remembers a successful key validation for a few seconds.
 *
 * <p>Every request through the gateway authenticates, and authenticating meant a network call to
 * auth-service — per request, forever, for a key whose answer changes almost never. That call is
 * on the critical path of every payment, so it costs latency twice over: once in the response the
 * merchant waits for, and again in the auth-service capacity needed to answer it.
 *
 * <p>Three deliberate limits, because caching an authentication decision is exactly the kind of
 * optimisation that quietly becomes a security bug:
 *
 * <ol>
 *   <li><strong>Only successes are cached.</strong> A rejection is always re-asked. Caching "this
 *       key is invalid" would mean a key that was just issued stays broken for the TTL, and it
 *       would let a caller with a bad key learn nothing while still costing auth-service nothing —
 *       the throttle there is what handles abuse, and it needs to see the attempts to do that.
 *   <li><strong>The TTL is short and configurable.</strong> This is a revocation window: for up to
 *       that long, a revoked key still works. Seconds are a reasonable trade for removing a network
 *       hop from every request; minutes would not be. Set it to zero to switch caching off.
 *   <li><strong>Keys are stored hashed, never in plaintext.</strong> The cache is long-lived, and a
 *       long-lived map of live API keys is a heap dump away from being a credential dump. SHA-256
 *       of the key is enough to look an entry up and useless to anyone who steals it.
 * </ol>
 *
 * <p>Bounded, and safe to leave unbounded-looking: only validated keys ever become entries, so a
 * caller cannot grow this map by guessing. The cap is a backstop against a merchant with an
 * unreasonable number of live keys, not against an attacker.
 */
public class CachingAuthServiceClient implements AuthServiceClient {

    private static final Logger log = LoggerFactory.getLogger(CachingAuthServiceClient.class);
    private static final int MAX_ENTRIES = 10_000;

    private final AuthServiceClient delegate;
    private final Duration ttl;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    public CachingAuthServiceClient(AuthServiceClient delegate, Duration ttl) {
        this.delegate = delegate;
        this.ttl = ttl;
        log.info("API key validation cached for {}ms", ttl.toMillis());
    }

    @Override
    public ApiKeyPrincipal validateApiKey(String apiKey) {
        String cacheKey = hash(apiKey);
        long now = System.nanoTime();

        CacheEntry cached = entries.get(cacheKey);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.principal();
        }

        // Not cached, or expired. A failure here propagates untouched — an invalid key must stay
        // invalid and an unreachable auth-service must stay an outage, neither turned into a hit.
        ApiKeyPrincipal principal = delegate.validateApiKey(apiKey);

        if (entries.size() >= MAX_ENTRIES) {
            // Cheaper than tracking access order, and this should effectively never happen.
            entries.clear();
            log.warn("API key validation cache hit {} entries and was cleared", MAX_ENTRIES);
        }
        entries.put(cacheKey, new CacheEntry(principal, now + ttl.toNanos()));
        return principal;
    }

    private String hash(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not hash an API key for caching", exception);
        }
    }

    /** Nanos from {@link System#nanoTime()}, which cannot jump backwards when the clock is set. */
    private record CacheEntry(ApiKeyPrincipal principal, long expiresAtNanos) {
    }
}
