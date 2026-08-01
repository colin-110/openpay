package com.openpay.notification.infrastructure;

import com.openpay.security.OutboundUrlPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Refuses to resolve a host to any address the platform must not connect to.
 *
 * <p>This is where the SSRF hole is actually closed, and it is a DNS resolver rather than a check
 * before the request for a specific reason. Validating the URL and <em>then</em> connecting leaves
 * a window: the HTTP client resolves the name again when it opens the socket, so an attacker who
 * controls the DNS record can answer publicly for the check and with 169.254.169.254 for the
 * connection. That is DNS rebinding, and no amount of checking beforehand prevents it.
 *
 * <p>Resolving through this class removes the window, because the addresses it returns are the
 * addresses the connection manager connects to — there is no second lookup to poison. It also
 * covers every hop for free: a redirect the client chose to follow would be resolved through here
 * too.
 *
 * <p>Loopback is allowed only when explicitly enabled for local development, which is the one case
 * where the platform legitimately talks to itself.
 */
public class PublicAddressDnsResolver implements DnsResolver {

    private static final Logger log = LoggerFactory.getLogger(PublicAddressDnsResolver.class);

    private final DnsResolver delegate;
    private final boolean allowLoopback;

    public PublicAddressDnsResolver(boolean allowLoopback) {
        this(SystemDefaultDnsResolver.INSTANCE, allowLoopback);
    }

    PublicAddressDnsResolver(DnsResolver delegate, boolean allowLoopback) {
        this.delegate = delegate;
        this.allowLoopback = allowLoopback;
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        InetAddress[] resolved = delegate.resolve(host);
        if (resolved == null || resolved.length == 0) {
            throw new UnknownHostException(host);
        }

        for (InetAddress address : resolved) {
            if (permitted(address)) {
                continue;
            }
            // Refusing the whole resolution rather than filtering the offending address out. A name
            // that answers with one public and one link-local address is not a host with a
            // configuration quirk; it is a name doing something it has no reason to do.
            log.warn("Refusing to connect to {}: resolves to {}", host, address.getHostAddress());
            throw new UnknownHostException(
                    host + " resolves to " + address.getHostAddress() + ", which is not publicly routable");
        }
        return resolved;
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        // Resolve first, so the canonical lookup cannot be used as a way around the check.
        InetAddress[] resolved = resolve(host);
        return Arrays.stream(resolved)
                .map(InetAddress::getCanonicalHostName)
                .findFirst()
                .orElse(host);
    }

    private boolean permitted(InetAddress address) {
        return OutboundUrlPolicy.isPubliclyRoutable(address)
                || (allowLoopback && address.isLoopbackAddress());
    }
}
