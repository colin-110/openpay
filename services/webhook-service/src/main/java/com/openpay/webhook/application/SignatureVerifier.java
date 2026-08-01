package com.openpay.webhook.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Verifies that a callback really came from the provider it claims to be from, and that it was
 * sent just now.
 *
 * <p>This is the trust boundary of the whole platform: a callback is what moves a payment to
 * CAPTURED, so an unverified one is an instruction from a stranger to release funds.
 *
 * <p>The signature covers {@code timestamp.body}, not the body alone. Signing the body alone makes
 * every captured callback valid forever, and the only thing standing between that and a replayed
 * capture is the deduplication table — a table with no retention policy, so the protection would
 * quietly disappear the day someone prunes it for size. Binding the timestamp into the signature
 * makes freshness a property of the message rather than a property of the database, and it cannot
 * be edited in transit without breaking the signature.
 */
@Component
public class SignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(SignatureVerifier.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final WebhookProperties properties;

    public SignatureVerifier(WebhookProperties properties) {
        this.properties = properties;
    }

    public Result verify(String providerName, String timestamp, String rawBody, String presentedSignature) {
        String secret = properties.getSigningSecrets().get(providerName);
        if (secret == null || secret.isBlank()) {
            // Fail closed. An unknown provider is not a provider.
            log.warn("No signing secret configured for provider {}, refusing its callback", providerName);
            return Result.INVALID_SIGNATURE;
        }
        if (presentedSignature == null || presentedSignature.isBlank()) {
            return Result.INVALID_SIGNATURE;
        }
        if (timestamp == null || timestamp.isBlank()) {
            // Required, not optional. Treating a missing timestamp as "skip the freshness check"
            // would let an attacker opt out of it by deleting a header.
            log.warn("Refusing callback from {} with no timestamp", providerName);
            return Result.INVALID_SIGNATURE;
        }

        long sentAt;
        try {
            sentAt = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException exception) {
            return Result.INVALID_SIGNATURE;
        }

        String expected = hmac(secret, timestamp.trim() + "." + rawBody);
        // Constant time: a byte-by-byte comparison leaks how much of a forged signature was right.
        boolean signatureMatches = MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                presentedSignature.trim().getBytes(StandardCharsets.UTF_8));

        if (!signatureMatches) {
            return Result.INVALID_SIGNATURE;
        }

        // Checked only after the signature, so an unauthenticated caller learns nothing about our
        // clock or our tolerance by probing timestamps.
        if (!isFresh(sentAt)) {
            log.warn("Refusing callback from {} sent at {}, outside the {} window",
                    providerName, sentAt, properties.getTolerance());
            return Result.STALE;
        }
        return Result.VALID;
    }

    /**
     * Skew is allowed in both directions. A provider whose clock runs slightly fast is not
     * replaying anything, and refusing it would turn a clock difference into a payment that never
     * captures.
     */
    private boolean isFresh(long sentAtEpochSeconds) {
        Duration tolerance = properties.getTolerance();
        long now = Instant.now().getEpochSecond();
        return Math.abs(now - sentAtEpochSeconds) <= tolerance.toSeconds();
    }

    private String hmac(String secret, String signedPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not compute signature", exception);
        }
    }

    public enum Result {
        VALID,
        INVALID_SIGNATURE,
        /** Correctly signed, but outside the freshness window. Almost always a replay. */
        STALE
    }
}
