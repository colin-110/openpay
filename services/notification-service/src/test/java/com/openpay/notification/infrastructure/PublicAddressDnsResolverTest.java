package com.openpay.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.apache.hc.client5.http.DnsResolver;
import org.junit.jupiter.api.Test;

class PublicAddressDnsResolverTest {

    @Test
    void resolvesAnOrdinaryPublicHost() throws Exception {
        DnsResolver resolver = new PublicAddressDnsResolver(fixed("93.184.216.34"), false);

        assertThat(resolver.resolve("example.com")).hasSize(1);
    }

    @Test
    void refusesTheCloudMetadataAddress() {
        DnsResolver resolver = new PublicAddressDnsResolver(fixed("169.254.169.254"), false);

        assertThatThrownBy(() -> resolver.resolve("evil.example"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("not publicly routable");
    }

    /**
     * The attack this class exists for.
     *
     * <p>A name that answers publicly when the URL is checked and link-local when the request is
     * actually made. Anything that validated the URL up front and then connected would pass the
     * first answer and connect to the second. Because every resolution goes through the policy,
     * the second answer is refused too.
     */
    @Test
    void refusesAHostThatRebindsBetweenTheCheckAndTheConnection() throws Exception {
        DnsResolver resolver = new PublicAddressDnsResolver(
                sequence("93.184.216.34", "169.254.169.254"), false);

        assertThat(resolver.resolve("rebind.example")).hasSize(1);

        assertThatThrownBy(() -> resolver.resolve("rebind.example"))
                .isInstanceOf(UnknownHostException.class)
                .hasMessageContaining("169.254.169.254");
    }

    @Test
    void refusesTheWholeAnswerWhenOnlyOneAddressIsBad() {
        // A name answering with one public and one link-local address is not a quirk; it is a name
        // doing something it has no reason to do. Filtering the bad one out would let it keep
        // trying.
        DnsResolver resolver = new PublicAddressDnsResolver(fixed("93.184.216.34", "10.0.0.7"), false);

        assertThatThrownBy(() -> resolver.resolve("mixed.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void refusesPrivateRangesAndTheWildcardAddress() {
        for (String address : new String[] {"10.0.0.5", "192.168.1.10", "172.16.4.4", "0.0.0.0", "::1"}) {
            DnsResolver resolver = new PublicAddressDnsResolver(fixed(address), false);
            assertThatThrownBy(() -> resolver.resolve("internal.example"))
                    .as(address)
                    .isInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void allowsLoopbackOnlyWhenDevelopmentTurnsItOn() {
        assertThatThrownBy(() -> new PublicAddressDnsResolver(fixed("127.0.0.1"), false).resolve("localhost"))
                .isInstanceOf(UnknownHostException.class);

        assertThatCode(() -> new PublicAddressDnsResolver(fixed("127.0.0.1"), true).resolve("localhost"))
                .doesNotThrowAnyException();
    }

    @Test
    void theCanonicalLookupIsNotAWayAround() {
        // resolveCanonicalHostname is a second entry point on the same interface, so it has to
        // apply the same rule or it becomes the hole.
        DnsResolver resolver = new PublicAddressDnsResolver(fixed("169.254.169.254"), false);

        assertThatThrownBy(() -> resolver.resolveCanonicalHostname("evil.example"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void anEmptyAnswerIsAFailureRatherThanAPass() {
        DnsResolver resolver = new PublicAddressDnsResolver(
                new DnsResolver() {
                    @Override
                    public InetAddress[] resolve(String host) {
                        return new InetAddress[0];
                    }

                    @Override
                    public String resolveCanonicalHostname(String host) {
                        return host;
                    }
                },
                false);

        assertThatThrownBy(() -> resolver.resolve("empty.example")).isInstanceOf(UnknownHostException.class);
    }

    private static DnsResolver fixed(String... addresses) {
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                InetAddress[] resolved = new InetAddress[addresses.length];
                for (int index = 0; index < addresses.length; index++) {
                    resolved[index] = InetAddress.getByName(addresses[index]);
                }
                return resolved;
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }

    /** Answers differently on each call, which is what a rebinding record does. */
    private static DnsResolver sequence(String... addresses) {
        Deque<String> answers = new ArrayDeque<>();
        for (String address : addresses) {
            answers.add(address);
        }
        return new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                String next = answers.size() > 1 ? answers.poll() : answers.peek();
                return new InetAddress[] {InetAddress.getByName(next)};
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        };
    }
}
