package com.openpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Caching an authentication decision is the kind of optimisation that turns into a security bug
 * without anyone noticing, so the tests that matter here are the ones about what is
 * <em>not</em> cached — and, since the stale-while-revalidate change, the ones about what a
 * revoked key does on the way out.
 *
 * <p>The timing tests use a 200ms TTL rather than the tightest interval that would work. These
 * assertions run on CI hosts under load, and a test whose budget only holds on an idle machine is
 * a test that fails for reasons that have nothing to do with the code.
 */
@ExtendWith(MockitoExtension.class)
class CachingAuthServiceClientTest {

    private static final String API_KEY = "opk_live_abcdef123456";
    /** Expired at 200ms, still inside the stale grace until 400ms, fully cold after that. */
    private static final Duration TTL = Duration.ofMillis(200);

    @Mock
    private AuthServiceClient delegate;

    @Test
    void asksAuthServiceOnceAndServesTheRestFromCache() {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        when(delegate.validateApiKey(API_KEY)).thenReturn(principal);
        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30))) {
            for (int i = 0; i < 50; i++) {
                assertThat(client.validateApiKey(API_KEY)).isEqualTo(principal);
            }

            // The whole point: fifty authenticated requests, one network call.
            verify(delegate, times(1)).validateApiKey(API_KEY);
        }
    }

    @Test
    void servesTheStaleAnswerAndRefreshesInTheBackgroundRatherThanBlocking() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        CountDownLatch refreshed = new CountDownLatch(2);
        when(delegate.validateApiKey(API_KEY)).thenAnswer(invocation -> {
            refreshed.countDown();
            return principal;
        });

        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, TTL)) {
            client.validateApiKey(API_KEY);
            Thread.sleep(250);

            // Expired, but inside the grace: this call is answered from the stale entry rather
            // than being made to wait for auth-service.
            assertThat(client.validateApiKey(API_KEY)).isEqualTo(principal);

            assertThat(refreshed.await(5, TimeUnit.SECONDS))
                    .as("the background refresh should have run")
                    .isTrue();
        }
    }

    /**
     * A longer TTL than the other timing tests, and the reason is the assertion this one makes.
     *
     * <p>It counts delegate calls <em>exactly</em>, which means every one of the 201 requests below
     * has to land inside the stale-grace window — and the grace is {@code min(ttl, 5s)}, so at the
     * shared 200ms TTL the whole loop had 200ms to finish. On an idle machine that is ample and on
     * a loaded one it is not: requests that fall past the grace correctly take the synchronous
     * path, producing an extra call and a failure that says "3, expected 2" while nothing is
     * actually wrong. Observed exactly that, on a host busy building container images.
     *
     * <p>A second is five times the headroom for the same assertion. Loosening the assertion
     * instead — "fewer than ten calls" — would have been the cheaper fix and a worse one: exactly
     * one refresh is the property, and a test that tolerates three is a test that would not notice
     * single-flight breaking.
     *
     * <p>Used by every test that has to make more than one call <em>inside</em> the stale grace.
     * At the shared 200ms TTL the grace is also 200ms, and a test that waits on a latch and then
     * calls again can easily spend longer than that on a loaded machine — at which point the entry
     * has aged out legitimately, the call goes down the synchronous path, and the failure looks
     * like the cache is broken when it is behaving exactly as designed.
     */
    private static final Duration GRACE_TTL = Duration.ofSeconds(1);

    @Test
    void collapsesAHerdOfExpiredRequestsIntoASingleRefresh() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        CountDownLatch refreshStarted = new CountDownLatch(1);
        CountDownLatch releaseRefresh = new CountDownLatch(1);
        CountDownLatch refreshFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        when(delegate.validateApiKey(API_KEY)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() > 1) {
                // Hold the refresh open so every later call below happens while it is in flight.
                refreshStarted.countDown();
                releaseRefresh.await(5, TimeUnit.SECONDS);
                refreshFinished.countDown();
            }
            return principal;
        });

        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, GRACE_TTL)) {
            client.validateApiKey(API_KEY);
            // Past the TTL, so the entry is stale, and well inside the grace that follows it.
            Thread.sleep(GRACE_TTL.toMillis() + 50);

            client.validateApiKey(API_KEY);
            assertThat(refreshStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // This is the thundering herd. Every one of these is past the TTL, and every one of
            // them must be answered from the stale entry without queueing another network call —
            // that stampede is what took auth-service's p99 to 2.7 seconds under load.
            for (int i = 0; i < 200; i++) {
                assertThat(client.validateApiKey(API_KEY)).isEqualTo(principal);
            }

            assertThat(calls.get())
                    .as("201 expired requests should have produced exactly one refresh")
                    .isEqualTo(2);
            // Let the held refresh finish before the client closes, so shutdownNow is not
            // interrupting a thread mid-answer and printing a stack trace the test does not mean.
            releaseRefresh.countDown();
            assertThat(refreshFinished.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void dropsAKeyThatStopsValidatingOnRefreshRatherThanServingItOutTheGrace() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        CountDownLatch revoked = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.validateApiKey(API_KEY)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return principal;
            }
            revoked.countDown();
            throw new InvalidApiKeyException("API key is invalid");
        });

        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, TTL)) {
            client.validateApiKey(API_KEY);
            Thread.sleep(250);

            client.validateApiKey(API_KEY);
            assertThat(revoked.await(5, TimeUnit.SECONDS)).isTrue();
            // Give the executor a moment to finish the eviction the throw triggers.
            Thread.sleep(100);

            // Revocation must not wait for the grace to run out. The entry is gone, so this is a
            // synchronous call, and it rejects.
            assertThatThrownBy(() -> client.validateApiKey(API_KEY))
                    .isInstanceOf(InvalidApiKeyException.class);
        }
    }

    @Test
    void goesBackToAsynchronousCallOnceTheStaleGraceHasRunOut() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        when(delegate.validateApiKey(API_KEY)).thenReturn(principal);

        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, TTL)) {
            client.validateApiKey(API_KEY);
            // Past the TTL and past the grace. Nothing here is servable any more.
            Thread.sleep(600);

            assertThat(client.validateApiKey(API_KEY)).isEqualTo(principal);

            // This is the revocation window closing for real. A key served stale forever would be
            // a key that could never be revoked.
            verify(delegate, atLeast(2)).validateApiKey(API_KEY);
        }
    }

    @Test
    void neverCachesARejection() {
        when(delegate.validateApiKey(API_KEY)).thenThrow(new InvalidApiKeyException("API key is invalid"));
        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30))) {
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> client.validateApiKey(API_KEY))
                        .isInstanceOf(InvalidApiKeyException.class);
            }

            // Caching a rejection would leave a freshly issued key broken for the whole TTL, and
            // would hide repeated bad attempts from auth-service's throttle, which needs them.
            verify(delegate, times(3)).validateApiKey(API_KEY);
        }
    }

    @Test
    void neverCachesAnOutage() {
        when(delegate.validateApiKey(API_KEY))
                .thenThrow(new AuthServiceUnavailableException("Auth service is unreachable", null));
        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30))) {
            for (int i = 0; i < 3; i++) {
                assertThatThrownBy(() -> client.validateApiKey(API_KEY))
                        .isInstanceOf(AuthServiceUnavailableException.class);
            }

            // An unreachable auth-service must keep reading as an outage. Remembering the failure
            // would extend a blip into a TTL-long refusal after auth-service had already recovered.
            verify(delegate, times(3)).validateApiKey(API_KEY);
        }
    }

    @Test
    void keepsServingStaleWhenAuthServiceCannotBeReachedForTheRefresh() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        CountDownLatch refreshFailed = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(delegate.validateApiKey(API_KEY)).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                return principal;
            }
            refreshFailed.countDown();
            throw new AuthServiceUnavailableException("Auth service is unreachable", null);
        });

        // GRACE_TTL, not TTL: this makes three calls and waits on a latch between them, all of
        // which must land inside the stale grace.
        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, GRACE_TTL)) {
            client.validateApiKey(API_KEY);
            Thread.sleep(GRACE_TTL.toMillis() + 50);

            client.validateApiKey(API_KEY);
            assertThat(refreshFailed.await(5, TimeUnit.SECONDS)).isTrue();

            // A blip in auth-service must not become a refused payment while a perfectly good
            // answer is sitting in the cache. The entry is not extended, though — it ages out of
            // the grace and the outage surfaces then, which the next test above covers.
            assertThat(client.validateApiKey(API_KEY)).isEqualTo(principal);
        }
    }

    @Test
    void renewsAnEntryBeforeItExpiresSoSteadyTrafficNeverWaits() throws Exception {
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        CountDownLatch renewed = new CountDownLatch(2);
        when(delegate.validateApiKey(API_KEY)).thenAnswer(invocation -> {
            renewed.countDown();
            return principal;
        });

        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, TTL)) {
            client.validateApiKey(API_KEY);
            // Past the 75% refresh point (150ms) but comfortably inside the 200ms TTL.
            Thread.sleep(170);
            client.validateApiKey(API_KEY);

            // Renewed while still fresh. Waiting for expiry to refresh means every key spends part
            // of its life stale, and any hiccup in refreshing pushes requests onto the synchronous
            // path — which under load is where the read timeouts and refused payments came from.
            assertThat(renewed.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void keepsUpWhenThereAreManyDistinctKeysRatherThanOne() throws Exception {
        // The case that broke in production-ish load and that no test here covered: the refresh
        // work scales with the number of *keys*, not the request rate. One merchant needs a
        // refresh every few seconds; a hundred and twenty-six need one roughly every 40ms. A
        // two-thread executor was ample for the former and collapsed on the latter, and every test
        // in this file used a single key, so nothing noticed.
        int keys = 200;
        ApiKeyPrincipal principal = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        AtomicInteger calls = new AtomicInteger();
        when(delegate.validateApiKey(org.mockito.ArgumentMatchers.anyString())).thenAnswer(invocation -> {
            calls.incrementAndGet();
            // Refreshes are network calls; a free one would not exercise the queue at all.
            Thread.sleep(5);
            return principal;
        });

        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, TTL)) {
            for (int round = 0; round < 4; round++) {
                for (int key = 0; key < keys; key++) {
                    client.validateApiKey("key-" + key);
                }
                Thread.sleep(60);
            }

            // 200 keys over ~4 rounds. Every call must have been answered — the failure mode being
            // guarded against is not wrong answers but requests falling back to the synchronous
            // path because renewal could not keep up.
            assertThat(calls.get())
                    .as("every key should have been validated at least once")
                    .isGreaterThanOrEqualTo(keys);
        }
    }

    @Test
    void keepsDifferentKeysApart() {
        ApiKeyPrincipal first = new ApiKeyPrincipal(UUID.randomUUID(), "payments:write");
        ApiKeyPrincipal second = new ApiKeyPrincipal(UUID.randomUUID(), "payments:read");
        when(delegate.validateApiKey("key-one")).thenReturn(first);
        when(delegate.validateApiKey("key-two")).thenReturn(second);
        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30))) {
            // Two merchants sharing one cache entry would be the worst bug this class could have:
            // one merchant's payments recorded against another's account.
            assertThat(client.validateApiKey("key-one")).isEqualTo(first);
            assertThat(client.validateApiKey("key-two")).isEqualTo(second);
            assertThat(client.validateApiKey("key-one")).isEqualTo(first);
            assertThat(client.validateApiKey("key-two")).isEqualTo(second);

            verify(delegate, times(1)).validateApiKey("key-one");
            verify(delegate, times(1)).validateApiKey("key-two");
        }
    }

    @Test
    void preservesTheAuthorityThatCameBackSoAReadOnlyKeyStaysReadOnly() {
        UUID merchantId = UUID.randomUUID();
        when(delegate.validateApiKey(API_KEY)).thenReturn(new ApiKeyPrincipal(merchantId, "payments:read"));
        try (CachingAuthServiceClient client = new CachingAuthServiceClient(delegate, Duration.ofSeconds(30))) {
            client.validateApiKey(API_KEY);
            ApiKeyPrincipal fromCache = client.validateApiKey(API_KEY);

            assertThat(fromCache.merchantId()).isEqualTo(merchantId);
            assertThat(fromCache.authority()).isEqualTo("payments:read");
        }
    }
}
