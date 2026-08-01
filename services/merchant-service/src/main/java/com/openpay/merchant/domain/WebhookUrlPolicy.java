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
 * <p>This check runs when the URL is stored, which stops the obvious cases early and with a clear
 * error. It is not sufficient on its own: DNS can be repointed between this check and the send, so
 * a host that resolves publicly today can point at link-local tomorrow. Closing that needs the
 * dispatcher to re-resolve and re-check immediately before connecting, which is recorded as the
 * remaining half of this finding in docs/SECURITY-AUDIT.md and is not implemented yet.
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
            // Deliberately allowed. DNS is transient, and refusing a merchant's URL because its
            // domain happened not to resolve during onboarding would block a legitimate setup for
            // a reason that has nothing to do with the URL. It also makes this check depend on
            // network state, which would make it untestable offline. An unresolvable host simply
            // fails delivery and is recorded in the delivery log.
            log.info("Webhook host {} did not resolve; allowing it and leaving delivery to report", host);
            return;
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
