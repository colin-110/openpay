package com.openpay.webhook.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies that a callback really came from the provider it claims to be from.
 *
 * <p>This is the trust boundary of the whole platform: a callback is what moves a payment to
 * CAPTURED, so an unverified one is an instruction from a stranger to release funds.
 */
@Component
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebhookProperties properties;

    public SignatureVerifier(WebhookProperties properties) {
        this.properties = properties;
    }

    public boolean verify(String providerName, String rawBody, String presentedSignature) {
        String secret = properties.getSigningSecrets().get(providerName);
        if (secret == null || secret.isBlank()) {
            // Fail closed. An unknown provider is not a provider.
            log.warn("No signing secret configured for provider {}, refusing its callback", providerName);
            return false;
        }
        if (presentedSignature == null || presentedSignature.isBlank()) {
            return false;
        }

        String expected = hmac(secret, rawBody);
        // Constant time: a byte-by-byte comparison leaks how much of a forged signature was right.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedSignature.trim().getBytes(StandardCharsets.UTF_8));
    }

    private String hmac(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not compute signature", exception);
        }
    }
}
