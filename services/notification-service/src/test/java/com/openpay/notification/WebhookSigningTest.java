package com.openpay.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.notification.application.BackoffPolicy;
import com.openpay.notification.application.WebhookSigner;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WebhookSigningTest {

    private static final String SECRET = "merchant-secret";
    private static final String BODY = "{\"type\":\"payment.captured\",\"amount\":10000}";

    @Test
    void producesAStableSignatureForTheSameInputs() {
        assertThat(WebhookSigner.sign(SECRET, 1_700_000_000L, BODY))
                .isEqualTo(WebhookSigner.sign(SECRET, 1_700_000_000L, BODY));
    }

    @Test
    void theTimestampIsPartOfWhatIsSigned() {
        // Signing the body alone would let anyone who captured one delivery replay it forever.
        assertThat(WebhookSigner.sign(SECRET, 1_700_000_000L, BODY))
                .isNotEqualTo(WebhookSigner.sign(SECRET, 1_700_000_001L, BODY));
    }

    @Test
    void tamperingWithTheBodyBreaksTheSignature() {
        String tampered = BODY.replace("10000", "1");

        assertThat(WebhookSigner.sign(SECRET, 1_700_000_000L, tampered))
                .isNotEqualTo(WebhookSigner.sign(SECRET, 1_700_000_000L, BODY));
    }

    @Test
    void aDifferentMerchantSecretProducesADifferentSignature() {
        assertThat(WebhookSigner.sign("other-secret", 1_700_000_000L, BODY))
                .isNotEqualTo(WebhookSigner.sign(SECRET, 1_700_000_000L, BODY));
    }

    @Test
    void backoffDoublesUntilItHitsTheCap() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThat(policy.backoffAfter(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.backoffAfter(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.backoffAfter(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.backoffAfter(4)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void backoffNeverExceedsTheCap() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10));

        assertThat(policy.backoffAfter(8)).isEqualTo(Duration.ofMinutes(10));
        assertThat(policy.backoffAfter(20)).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void aLargeAttemptCountCannotOverflowIntoANegativeDelay() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(5), Duration.ofHours(6));

        // Unbounded doubling would wrap and schedule the retry in the past.
        assertThat(policy.backoffAfter(1000)).isEqualTo(Duration.ofHours(6));
        assertThat(policy.backoffAfter(Integer.MAX_VALUE)).isPositive();
    }
}
