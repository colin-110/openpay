package com.openpay.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class OutboundUrlPolicyTest {

    @Test
    void acceptsAnOrdinaryHttpsEndpoint() {
        assertThatCode(() -> OutboundUrlPolicy.requireDeliverable("https://example.com/hooks", false))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesTheCloudMetadataEndpoint() {
        // The reason this class exists. 169.254.169.254 returns IAM credentials on EC2.
        assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable(
                "http://169.254.169.254/latest/meta-data/", false))
                .isInstanceOf(UndeliverableUrlException.class)
                .hasMessageContaining("not publicly routable");
    }

    @Test
    void refusesPrivateAndLoopbackAddresses() {
        for (String url : new String[] {
                "https://10.0.0.5/hook", "https://192.168.1.1/hook", "https://127.0.0.1/hook"}) {
            assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable(url, false))
                    .as(url)
                    .isInstanceOf(UndeliverableUrlException.class);
        }
    }

    @Test
    void refusesReachingAnotherServiceOnThisHost() {
        assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable(
                "http://localhost:8086/api/v1/ledger/entries", false))
                .isInstanceOf(UndeliverableUrlException.class);
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyPermitted() {
        assertThatCode(() -> OutboundUrlPolicy.requireDeliverable("http://localhost:9999/hook", true))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesNonHttpSchemes() {
        assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable("file:///etc/passwd", false))
                .isInstanceOf(UndeliverableUrlException.class);
        assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable("gopher://example.com/", false))
                .isInstanceOf(UndeliverableUrlException.class);
    }

    @Test
    void refusesPlainHttpToAPublicHost() {
        assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable("http://example.com/hook", false))
                .isInstanceOf(UndeliverableUrlException.class)
                .hasMessageContaining("https");
    }

    @Test
    void refusesCredentialsEmbeddedInTheUrl() {
        assertThatThrownBy(() -> OutboundUrlPolicy.requireDeliverable(
                "https://user:secret@example.com/hook", false))
                .isInstanceOf(UndeliverableUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void treatsAnAbsentUrlAsFineBecauseItIsOptional() {
        assertThatCode(() -> OutboundUrlPolicy.requireDeliverable(null, false)).doesNotThrowAnyException();
        assertThatCode(() -> OutboundUrlPolicy.requireDeliverable("  ", false)).doesNotThrowAnyException();
    }

    @Test
    void anUnresolvableHostIsLeftToTheConnectTimeCheck() {
        // DNS is transient. Refusing here would block a legitimate setup for a reason unrelated to
        // the URL, and nothing is lost: the resolver refuses the connection if it ever resolves
        // somewhere it should not.
        assertThatCode(() -> OutboundUrlPolicy.requireDeliverable(
                "https://not-a-real-host.invalid/hook", false))
                .doesNotThrowAnyException();
    }

    @Test
    void classifiesAddressesTheWayTheResolverNeeds() throws Exception {
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("93.184.216.34"))).isTrue();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("10.1.2.3"))).isFalse();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("172.16.0.1"))).isFalse();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("0.0.0.0"))).isFalse();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("::1"))).isFalse();
        assertThat(OutboundUrlPolicy.isPubliclyRoutable(InetAddress.getByName("fe80::1"))).isFalse();
    }
}
