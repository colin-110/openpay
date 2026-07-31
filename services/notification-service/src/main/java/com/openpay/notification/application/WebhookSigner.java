package com.openpay.notification.application;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs an outbound webhook so the merchant can prove it came from us.
 *
 * <p>The signature covers the timestamp and the body together. Signing the body alone would let
 * anyone who captured one delivery replay it forever, so the merchant is expected to reject a
 * timestamp that is too old as well as a signature that does not match.
 */
public final class WebhookSigner {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    public static String sign(String secret, long timestampSeconds, String body) {
        String signedPayload = timestampSeconds + "." + body;
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign webhook", exception);
        }
    }

    private WebhookSigner() {
    }
}
