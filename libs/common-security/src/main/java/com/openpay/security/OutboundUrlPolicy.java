package com.openpay.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether the platform is willing to make an outbound request to a given URL or address.
 *
 * <p>Without this, whatever string sits in {@code merchants.webhook_url} is a URL the platform will
 * POST to from inside its own network, with a valid signature attached. That is a server-side
 * request forgery primitive: {@code http://169.254.169.254/latest/meta-data/} on EC2,
 * {@code http://metadata.google.internal/}, or simply another OpenPay service that is not supposed
 * to be reachable from outside.
 *
 * <p>Shared rather than duplicated, because it is applied at two moments that must agree.
 * {@link #requireDeliverable} runs when a URL is stored, so a bad one is rejected immediately with
 * a clear error. {@link #isPubliclyRoutable} runs again inside DNS resolution at the moment of
 * connecting, which is what actually closes the hole: a name that resolved publicly when it was
 * saved can be repointed at link-local before the request goes out, and only the check that runs
 * against the address being connected to can catch that.
 */
public final class OutboundUrlPolicy {

    private static final Logger log = LoggerFactory.getLogger(OutboundUrlPolicy.class);

    private OutboundUrlPolicy() {
    }

    /**
     * Anything that is not a public internet address: loopback, link-local (which covers the cloud
     * metadata endpoints at 169.254.169.254 and fd00:ec2::254), private ranges, multicast, and the
     * wildcard address.
     */
    public static boolean isPubliclyRoutable(InetAddress address) {
        return !(address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress());
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
            throw new UndeliverableUrlException("is not a valid URL");
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        boolean https = scheme.equals("https");
        boolean http = scheme.equals("http");
        if (!https && !http) {
            throw new UndeliverableUrlException("must use https");
        }

        if (uri.getUserInfo() != null) {
            // Credentials in a URL would be logged, stored, and sent onward in plain sight.
            throw new UndeliverableUrlException("must not contain credentials");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new UndeliverableUrlException("must name a host");
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            // Deliberately allowed. DNS is transient, and refusing a merchant's URL because its
            // domain happened not to resolve during onboarding would block a legitimate setup for
            // a reason that has nothing to do with the URL. Nothing is lost by allowing it: the
            // connect-time check is the one that actually protects the network.
            log.info("Webhook host {} did not resolve; the connect-time check still applies", host);
            return;
        }

        for (InetAddress address : resolved) {
            if (address.isLoopbackAddress() && allowLoopback && http) {
                // A developer pointing at their own machine is the one legitimate plain-http case.
                continue;
            }
            if (!isPubliclyRoutable(address)) {
                log.warn("Refused webhook URL resolving to {}", address.getHostAddress());
                throw new UndeliverableUrlException("resolves to an address that is not publicly routable");
            }
            if (!https) {
                throw new UndeliverableUrlException("must use https");
            }
        }
    }
}
