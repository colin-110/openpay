package com.openpay.security;

import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * Pooled HTTP clients for service-to-service calls.
 *
 * <p>Every internal client on this platform used {@link
 * org.springframework.http.client.SimpleClientHttpRequestFactory}, which wraps {@code
 * HttpURLConnection} and keeps no connections at all. Each call therefore paid a full TCP handshake
 * and left a socket in {@code TIME_WAIT} behind it — twice per payment, since creating one crosses
 * gateway to payment-service and then payment-service to fraud-service. Under sustained load that
 * is not only latency but a slow walk toward ephemeral port exhaustion, which fails in a way that
 * looks random and intermittent rather than like a resource limit.
 *
 * <p>notification-service already built a pooled client for outbound merchant webhooks, with a
 * custom DNS resolver to stop a merchant URL resolving to a link-local address. That protection is
 * deliberately absent here: these clients call named services inside the compose network, and the
 * hostnames are ours rather than a merchant's. Anything calling an address supplied by someone else
 * should keep using the notification-service builder, not this one.
 */
public final class InternalHttpClients {

    private InternalHttpClients() {
    }

    /**
     * @param maxPerRoute connections kept to a single peer. One route per downstream service, so
     *     this is effectively the concurrency ceiling for calls to that service — it wants to be at
     *     least as large as the caller's own request concurrency, or requests queue here instead of
     *     at the thing that is actually busy.
     */
    public static ClientHttpRequestFactory pooled(
            Duration connectTimeout, Duration readTimeout, int maxPerRoute) {

        PoolingHttpClientConnectionManager connectionManager =
                PoolingHttpClientConnectionManagerBuilder.create()
                        .setMaxConnPerRoute(maxPerRoute)
                        .setMaxConnTotal(maxPerRoute * 2)
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                                // Revalidate a connection idle longer than this before handing it
                                // out. Without it a pooled socket the peer has already closed —
                                // after a restart, or a proxy idle timeout — is handed to a caller
                                // and fails on first write, which reads as a random 500.
                                .setValidateAfterInactivity(TimeValue.ofSeconds(2))
                                // Retire connections eventually regardless. Long-lived sockets
                                // survive a downstream service being replaced, and would keep
                                // pointing at an instance that no longer exists.
                                .setTimeToLive(TimeValue.ofMinutes(5))
                                .build())
                        .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(connectionManager)
                // Idle connections are worth keeping, but not forever, and not once the peer is
                // gone. Both sweeps are cheap and run on the client's own background thread.
                .evictIdleConnections(TimeValue.ofSeconds(30))
                .evictExpiredConnections()
                // An internal service has no business redirecting a peer, and following one would
                // mean trusting a Location header to decide where a payment request goes.
                .disableRedirectHandling()
                // Retries belong to the caller, which knows whether the operation is safe to repeat.
                // A blanket retry here would silently re-send a payment creation.
                .disableAutomaticRetries()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                        // How long to wait for a connection from the pool. Short on purpose: if the
                        // pool is exhausted the downstream service is already the bottleneck, and
                        // queueing here just converts a fast failure into a slow one.
                        .setConnectionRequestTimeout(Timeout.ofMilliseconds(
                                Math.max(500, connectTimeout.toMillis())))
                        .build())
                .build();

        return new HttpComponentsClientHttpRequestFactory(httpClient);
    }
}
