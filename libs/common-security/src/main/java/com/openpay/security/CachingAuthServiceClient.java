package com.openpay.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
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
 * <h2>Why expiry does not mean "everybody calls auth-service at once"</h2>
 *
 * <p>A plain TTL cache has a failure mode that only appears under load, and it was measured here
 * rather than theorised: at expiry, <em>every</em> concurrent request holding that key misses
 * together and stampedes the delegate. At 150 requests a second against a 5-second TTL that is a
 * ~150-way thundering herd every five seconds, aimed at the one service every single request must
 * consult. Observed effect was a p50 of 15ms alongside a p99 of 2.7s, and then real
 * {@code validate-key} read timeouts — which the gateway correctly turns into a refused payment,
 * because it fails closed. A cache that collapses under the load it exists to absorb is worse than
 * no cache, because the failure arrives exactly when traffic does.
 *
 * <p>So an expired entry is <em>served stale</em> for a further grace period while exactly one
 * background refresh runs for that key. Consequences, stated plainly:
 *
 * <ul>
 *   <li>The revocation window is {@code ttl + staleGrace}, not {@code ttl}. That is the price, and
 *       it is why the grace is bounded and short rather than "until the refresh succeeds".
 *   <li>A refresh that comes back <em>invalid</em> evicts immediately, so a revoked key stops
 *       working at the refresh rather than at the end of the grace.
 *   <li>A refresh that cannot reach auth-service leaves the stale entry alone and does not extend
 *       it. The entry ages out of the grace, and callers go back to a synchronous call that
 *       propagates the outage — an outage stays an outage, it is just not one the first request
 *       through the door has to discover.
 *   <li>Past the grace, behaviour is exactly the old behaviour: a synchronous call whose failures
 *       propagate untouched.
 * </ul>
 *
 * <p>Bounded, and safe to leave unbounded-looking: only validated keys ever become entries, so a
 * caller cannot grow this map by guessing. The cap is a backstop against a merchant with an
 * unreasonable number of live keys, not against an attacker.
 */
public class CachingAuthServiceClient implements AuthServiceClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CachingAuthServiceClient.class);
    private static final int MAX_ENTRIES = 10_000;

    /**
     * How long past expiry a stale answer may still be served while a refresh runs.
     *
     * <p>Capped at the TTL itself so the revocation window can never more than double, and at five
     * seconds so a long TTL cannot quietly buy a long stale window on top.
     */
    private static final Duration MAX_STALE_GRACE = Duration.ofSeconds(5);
    private static final int REFRESH_THREADS = 8;
    /** Bounded, and overflow is dropped: a discarded refresh costs one stale answer, nothing more. */
    private static final int REFRESH_QUEUE = 1_000;

    /**
     * Refresh at this fraction of the TTL rather than waiting for expiry.
     *
     * <p>Serving stale works, but it is a recovery, not a plan: it only happens once an entry has
     * already gone out of date, so every key spends part of its life in the degraded state and any
     * hiccup in refreshing pushes requests onto the synchronous path. Refreshing early means that
     * under steady traffic an entry is renewed while it is still fresh and no request is ever
     * answered from a stale value at all. It also shortens the revocation window rather than
     * lengthening it, which is the right direction for the one trade this class makes.
     */
    private static final double REFRESH_AT = 0.75;

    private final AuthServiceClient delegate;
    private final Duration ttl;
    private final long staleGraceNanos;
    private final long refreshAfterNanos;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();
    /** Keys with a refresh in flight. Presence is the lock; the value is a placeholder. */
    private final Map<String, Boolean> refreshing = new ConcurrentHashMap<>();
    private final ExecutorService refreshExecutor;

    public CachingAuthServiceClient(AuthServiceClient delegate, Duration ttl) {
        this.delegate = delegate;
        this.ttl = ttl;
        this.staleGraceNanos = Math.min(ttl.toNanos(), MAX_STALE_GRACE.toNanos());
        this.refreshAfterNanos = (long) (ttl.toNanos() * REFRESH_AT);

        // Sized for the number of *distinct keys* in flight, not for the request rate — single
        // flight already collapses a key's traffic to one refresh, so the work is one bounded HTTP
        // call per key per TTL. That is a much larger number than it first looks: a gateway serving
        // 126 merchants on a 5-second TTL needs ~25 refreshes a second sustained.
        //
        // This started at two threads, which was ample for the one merchant the load tests used to
        // drive and badly undersized the moment they drove a realistic number. The queue backed up,
        // entries aged past the stale grace faster than they could be renewed, and requests fell
        // through to the synchronous path — where, under load, they hit the read timeout and the
        // gateway turned them into refused payments. Measured at 41% failures on the top tier.
        //
        // Daemon threads: this is a cache warmer, and it must never be the reason a JVM will not
        // exit. The queue is bounded and overflow is discarded rather than run on the caller: a
        // dropped refresh costs one stale answer, whereas CallerRunsPolicy would put an HTTP call
        // back on the request thread, which is precisely what this class exists to avoid.
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                REFRESH_THREADS, REFRESH_THREADS,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(REFRESH_QUEUE),
                runnable -> {
                    Thread thread = new Thread(runnable, "api-key-cache-refresh");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardPolicy());
        this.refreshExecutor = executor;

        log.info("API key validation cached for {}ms, refreshed after {}ms, served stale for up to "
                        + "a further {}ms",
                ttl.toMillis(), Duration.ofNanos(refreshAfterNanos).toMillis(),
                Duration.ofNanos(staleGraceNanos).toMillis());
    }

    @Override
    public ApiKeyPrincipal validateApiKey(String apiKey) {
        String cacheKey = hash(apiKey);
        long now = System.nanoTime();

        CacheEntry cached = entries.get(cacheKey);
        if (cached != null) {
            if (cached.expiresAtNanos() > now) {
                // Still fresh — but if it is close enough to expiry, renew it now so that the
                // requests arriving over the next second are answered from a fresh entry rather
                // than from a stale one. Under steady traffic this is the only path that ever
                // schedules a refresh, and the two below become dead ends nothing reaches.
                if (cached.refreshAfterNanos() <= now) {
                    scheduleRefresh(apiKey, cacheKey);
                }
                return cached.principal();
            }
            if (now - cached.expiresAtNanos() < staleGraceNanos) {
                // Expired, but not by much. Answer from what we have and refresh out of band, so
                // the cost of expiry is paid by a background thread instead of by however many
                // requests happen to be in flight at that instant.
                scheduleRefresh(apiKey, cacheKey);
                return cached.principal();
            }
        }

        // Nothing usable. A failure here propagates untouched — an invalid key must stay invalid
        // and an unreachable auth-service must stay an outage, neither turned into a hit.
        ApiKeyPrincipal principal = delegate.validateApiKey(apiKey);
        store(cacheKey, principal, System.nanoTime());
        return principal;
    }

    private void scheduleRefresh(String apiKey, String cacheKey) {
        // putIfAbsent is the single-flight gate: whoever wins runs the refresh, everyone else keeps
        // the stale answer and moves on. Without this, "serve stale" would still let the whole herd
        // through, just with better latency for the ones that got an answer.
        if (refreshing.putIfAbsent(cacheKey, Boolean.TRUE) != null) {
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    ApiKeyPrincipal refreshed = delegate.validateApiKey(apiKey);
                    store(cacheKey, refreshed, System.nanoTime());
                } catch (InvalidApiKeyException revoked) {
                    // The key stopped being valid. Drop it now rather than serving it for the rest
                    // of the grace — this is the whole point of re-asking.
                    entries.remove(cacheKey);
                    log.debug("API key no longer validates on refresh; dropped from cache");
                } catch (RuntimeException unavailable) {
                    // Leave the stale entry as it is, deliberately un-extended, so it ages out of
                    // the grace and the next caller discovers the outage synchronously.
                    log.debug("Background refresh of an API key failed, keeping the stale entry",
                            unavailable);
                } finally {
                    refreshing.remove(cacheKey);
                }
            });
        } catch (RejectedExecutionException shuttingDown) {
            refreshing.remove(cacheKey);
        }
    }

    private void store(String cacheKey, ApiKeyPrincipal principal, long now) {
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(cacheKey)) {
            // Cheaper than tracking access order, and this should effectively never happen.
            entries.clear();
            log.warn("API key validation cache hit {} entries and was cleared", MAX_ENTRIES);
        }
        entries.put(cacheKey, new CacheEntry(
                principal, now + ttl.toNanos(), now + refreshAfterNanos));
    }

    /** Stops the refresh threads. Spring calls this on shutdown; nothing else needs to. */
    @Override
    public void close() {
        refreshExecutor.shutdownNow();
        try {
            refreshExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
    private record CacheEntry(ApiKeyPrincipal principal, long expiresAtNanos, long refreshAfterNanos) {
    }
}
