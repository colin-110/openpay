package com.openpay.merchant.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookUrlPolicyTest {

    @Test
    void acceptsAnOrdinaryHttpsEndpoint() {
        assertThatCode(() -> WebhookUrlPolicy.requireDeliverable("https://example.com/hooks", false))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesTheCloudMetadataEndpoint() {
        // The reason this class exists. 169.254.169.254 returns IAM credentials on EC2, and the
        // platform would fetch them from inside the network and POST them nowhere useful — but a
        // redirect or an error body echoing the request is enough to leak.
        assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable(
                "http://169.254.169.254/latest/meta-data/", false))
                .isInstanceOf(UndeliverableWebhookUrlException.class)
                .hasMessageContaining("not publicly routable");
    }

    @Test
    void refusesPrivateAndLoopbackAddresses() {
        for (String url : new String[] {
                "https://10.0.0.5/hook", "https://192.168.1.1/hook", "https://127.0.0.1/hook"}) {
            assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable(url, false))
                    .as(url)
                    .isInstanceOf(UndeliverableWebhookUrlException.class);
        }
    }

    @Test
    void refusesReachingAnotherServiceOnThisHost() {
        assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable(
                "http://localhost:8086/api/v1/ledger/entries", false))
                .isInstanceOf(UndeliverableWebhookUrlException.class);
    }

    @Test
    void allowsLoopbackOnlyWhenExplicitlyPermitted() {
        assertThatCode(() -> WebhookUrlPolicy.requireDeliverable("http://localhost:9999/hook", true))
                .doesNotThrowAnyException();
    }

    @Test
    void refusesNonHttpSchemes() {
        assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable("file:///etc/passwd", false))
                .isInstanceOf(UndeliverableWebhookUrlException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable("gopher://example.com/", false))
                .isInstanceOf(UndeliverableWebhookUrlException.class);
    }

    @Test
    void refusesPlainHttpToAPublicHost() {
        assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable("http://example.com/hook", false))
                .isInstanceOf(UndeliverableWebhookUrlException.class)
                .hasMessageContaining("https");
    }

    @Test
    void refusesCredentialsEmbeddedInTheUrl() {
        assertThatThrownBy(() -> WebhookUrlPolicy.requireDeliverable(
                "https://user:secret@example.com/hook", false))
                .isInstanceOf(UndeliverableWebhookUrlException.class)
                .hasMessageContaining("credentials");
    }

    @Test
    void treatsAnAbsentUrlAsFineBecauseItIsOptional() {
        assertThatCode(() -> WebhookUrlPolicy.requireDeliverable(null, false)).doesNotThrowAnyException();
        assertThatCode(() -> WebhookUrlPolicy.requireDeliverable("  ", false)).doesNotThrowAnyException();
    }
}
