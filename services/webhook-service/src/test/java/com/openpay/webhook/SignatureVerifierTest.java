package com.openpay.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.webhook.application.SignatureVerifier;
import com.openpay.webhook.application.WebhookProperties;
import java.nio.charset.StandardCharsets;
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
        verifier = new SignatureVerifier(properties);
    }

    @Test
    void acceptsACorrectlySignedBody() {
        assertThat(verifier.verify("mock-bank-a", BODY, sign(SECRET, BODY))).isTrue();
    }

    @Test
    void rejectsASignatureFromTheWrongSecret() {
        assertThat(verifier.verify("mock-bank-a", BODY, sign("wrong-secret", BODY))).isFalse();
    }

    @Test
    void rejectsWhenTheBodyWasTamperedWith() {
        String signature = sign(SECRET, BODY);
        String tampered = BODY.replace("CAPTURED", "AUTHORIZED");

        // The whole point: an attacker cannot change the outcome and keep a valid signature.
        assertThat(verifier.verify("mock-bank-a", tampered, signature)).isFalse();
    }

    @Test
    void rejectsAMissingSignature() {
        assertThat(verifier.verify("mock-bank-a", BODY, null)).isFalse();
        assertThat(verifier.verify("mock-bank-a", BODY, "  ")).isFalse();
    }

    @Test
    void failsClosedForAnUnknownProvider() {
        // An unconfigured provider is not a provider, whatever signature it presents.
        assertThat(verifier.verify("who-is-this", BODY, sign(SECRET, BODY))).isFalse();
    }

    @Test
    void failsClosedWhenTheSecretIsBlank() {
        // A blank secret is a misconfiguration. No signature can be valid against it, so the
        // verifier must refuse before it ever tries to compute one.
        assertThat(verifier.verify("no-secret-bank", BODY, "any-signature-at-all")).isFalse();
    }

    private String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
