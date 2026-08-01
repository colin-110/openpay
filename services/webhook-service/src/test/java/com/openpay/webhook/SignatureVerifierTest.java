package com.openpay.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.webhook.application.SignatureVerifier;
import com.openpay.webhook.application.SignatureVerifier.Result;
import com.openpay.webhook.application.WebhookProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SignatureVerifierTest {

    private static final String SECRET = "top-secret";
    private static final String BODY = "{\"eventId\":\"e1\",\"outcome\":\"CAPTURED\"}";

    private SignatureVerifier verifier;

    @BeforeEach
    void setUp() {
        WebhookProperties properties = new WebhookProperties();
        properties.setSigningSecrets(Map.of("mock-bank-a", SECRET, "no-secret-bank", ""));
        properties.setTolerance(Duration.ofMinutes(5));
        verifier = new SignatureVerifier(properties);
    }

    @Test
    void acceptsACorrectlySignedRecentCallback() {
        long now = Instant.now().getEpochSecond();

        assertThat(verifier.verify("mock-bank-a", String.valueOf(now), BODY, sign(SECRET, now, BODY)))
                .isEqualTo(Result.VALID);
    }

    @Test
    void rejectsASignatureFromTheWrongSecret() {
        long now = Instant.now().getEpochSecond();

        assertThat(verifier.verify("mock-bank-a", String.valueOf(now), BODY, sign("wrong-secret", now, BODY)))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsWhenTheBodyWasTamperedWith() {
        long now = Instant.now().getEpochSecond();
        String signature = sign(SECRET, now, BODY);
        String tampered = BODY.replace("CAPTURED", "AUTHORIZED");

        // The whole point: an attacker cannot change the outcome and keep a valid signature.
        assertThat(verifier.verify("mock-bank-a", String.valueOf(now), tampered, signature))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    /**
     * The finding this was written for. A callback captured off the wire stays byte-for-byte valid
     * forever if the signature covers only the body; binding the timestamp in means it expires.
     */
    @Test
    void rejectsACallbackCapturedAndReplayedLater() {
        long anHourAgo = Instant.now().minus(Duration.ofHours(1)).getEpochSecond();

        // Perfectly signed, genuinely from the provider — just old.
        assertThat(verifier.verify(
                        "mock-bank-a", String.valueOf(anHourAgo), BODY, sign(SECRET, anHourAgo, BODY)))
                .isEqualTo(Result.STALE);
    }

    @Test
    void theTimestampCannotBeEditedToMakeAnOldCallbackLookFresh() {
        long anHourAgo = Instant.now().minus(Duration.ofHours(1)).getEpochSecond();
        String capturedSignature = sign(SECRET, anHourAgo, BODY);
        long now = Instant.now().getEpochSecond();

        // Replaying the captured signature with a fresh timestamp fails, because the timestamp is
        // inside what was signed. Without that, the freshness check would be trivially bypassed.
        assertThat(verifier.verify("mock-bank-a", String.valueOf(now), BODY, capturedSignature))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void allowsClockSkewInBothDirections() {
        long slightlyFast = Instant.now().plus(Duration.ofMinutes(2)).getEpochSecond();

        // A provider whose clock runs fast is not replaying anything, and refusing it would turn a
        // clock difference into payments that never capture.
        assertThat(verifier.verify(
                        "mock-bank-a", String.valueOf(slightlyFast), BODY, sign(SECRET, slightlyFast, BODY)))
                .isEqualTo(Result.VALID);
    }

    @Test
    void rejectsAFutureTimestampBeyondTolerance() {
        long farFuture = Instant.now().plus(Duration.ofHours(2)).getEpochSecond();

        assertThat(verifier.verify(
                        "mock-bank-a", String.valueOf(farFuture), BODY, sign(SECRET, farFuture, BODY)))
                .isEqualTo(Result.STALE);
    }

    @Test
    void rejectsAMissingTimestamp() {
        // Missing must not mean "skip the freshness check", or an attacker opts out by deleting a
        // header.
        assertThat(verifier.verify("mock-bank-a", null, BODY, sign(SECRET, 0, BODY)))
                .isEqualTo(Result.INVALID_SIGNATURE);
        assertThat(verifier.verify("mock-bank-a", "  ", BODY, sign(SECRET, 0, BODY)))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsATimestampThatIsNotANumber() {
        assertThat(verifier.verify("mock-bank-a", "yesterday", BODY, sign(SECRET, 0, BODY)))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void rejectsAMissingSignature() {
        String now = String.valueOf(Instant.now().getEpochSecond());

        assertThat(verifier.verify("mock-bank-a", now, BODY, null)).isEqualTo(Result.INVALID_SIGNATURE);
        assertThat(verifier.verify("mock-bank-a", now, BODY, "  ")).isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void failsClosedForAnUnknownProvider() {
        long now = Instant.now().getEpochSecond();

        // An unconfigured provider is not a provider, whatever signature it presents.
        assertThat(verifier.verify("who-is-this", String.valueOf(now), BODY, sign(SECRET, now, BODY)))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void failsClosedWhenTheSecretIsBlank() {
        // A blank secret is a misconfiguration. No signature can be valid against it, so the
        // verifier must refuse before it ever tries to compute one.
        String now = String.valueOf(Instant.now().getEpochSecond());

        assertThat(verifier.verify("no-secret-bank", now, BODY, "any-signature-at-all"))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    @Test
    void aStaleCallbackIsReportedAsStaleOnlyWhenItsSignatureIsGenuine() {
        long anHourAgo = Instant.now().minus(Duration.ofHours(1)).getEpochSecond();

        // An unauthenticated caller must not learn our clock or our tolerance by probing
        // timestamps, so a bad signature is reported as such regardless of how old it claims to be.
        assertThat(verifier.verify(
                        "mock-bank-a", String.valueOf(anHourAgo), BODY, sign("wrong-secret", anHourAgo, BODY)))
                .isEqualTo(Result.INVALID_SIGNATURE);
    }

    private String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String signedPayload = timestampSeconds + "." + body;
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
