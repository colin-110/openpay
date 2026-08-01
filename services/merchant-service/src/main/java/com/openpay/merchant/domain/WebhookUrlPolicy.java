package com.openpay.merchant.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether the platform is willing to send a merchant's webhooks to a given URL.
 *
 * <p>Without this, whatever string sits in {@code merchants.webhook_url} is a URL the platform will
 * POST to from inside its own network, with a valid signature attached. That is a
 * server-side request forgery primitive: {@code http://169.254.169.254/latest/meta-data/} on EC2,
 * {@code http://metadata.google.internal/}, or simply another OpenPay service that is not supposed
 * to be reachable from outside.
 *
 * <p>Checking at the point the URL is stored is necessary but not sufficient on its own — DNS can
 * be repointed after the check — so the dispatcher re-resolves before sending. This is the cheap
 * half that rejects the obvious cases early, with a clear error, before anything is persisted.
 */
public final class WebhookUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(WebhookUrlPolicy.class);

    private WebhookUrlPolicy() {
    }

    /** Thrown rather than returned so a bad URL cannot be stored by forgetting to check a boolean. */
    public static void requireDeliverable(String url, boolean allowLoopback) {
        if (url == null || url.isBlank()) {
            return;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new UndeliverableWebhookUrlException("is not a valid URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean https = scheme.equals("https");
        boolean http = scheme.equals("http");
        if (!https && !http) {
            throw new UndeliverableWebhookUrlException("must use https");
        }

        if (uri.getUserInfo() != null) {
            // Credentials in a URL would be logged, stored, and sent onward in plain sight.
            throw new UndeliverableWebhookUrlException("must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UndeliverableWebhookUrlException("must name a host");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new UndeliverableWebhookUrlException("names a host that does not resolve");
        }

        for (InetAddress address : resolved) {
            if (address.isLoopbackAddress() && allowLoopback && http) {
                // A developer pointing at their own machine is the one legitimate plain-http case.
                continue;
            }
            if (isReserved(address)) {
                log.warn("Refused webhook URL resolving to {}", address.getHostAddress());
                throw new UndeliverableWebhookUrlException(
                        "resolves to an address that is not publicly routable");
            }
            if (!https) {
                throw new UndeliverableWebhookUrlException("must use https");
            }
        }
    }

    /**
     * Anything that is not a public internet address: loopback, link-local (which covers the cloud
     * metadata endpoints at 169.254.169.254), private ranges, multicast, and the wildcard address.
     */
    private static boolean isReserved(InetAddress address) {
        return address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress();
    }
}
