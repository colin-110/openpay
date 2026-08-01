package com.openpay.mockbank.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.mockbank.domain.BankProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Sends the outcome back asynchronously, the way a real acquirer does.
 *
 * <p>A successful payment produces two callbacks, AUTHORIZED then CAPTURED, because that is the
 * real sequence and it gives the receiving side something genuine to order and deduplicate.
 */
@Component
public class CallbackSender {

    private static final Logger log = LoggerFactory.getLogger(CallbackSender.class);
    private static final String SIGNATURE_HEADER = "X-Provider-Signature";
    private static final String TIMESTAMP_HEADER = "X-Provider-Timestamp";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final BankProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public CallbackSender(BankProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().build();
    }

    @Async
    public void scheduleOutcome(UUID paymentId, String providerReference, boolean declined) {
        try {
            Thread.sleep(properties.getCallbackDelay().toMillis());

            if (declined) {
                send(paymentId, null, providerReference, "FAILED", "insufficient_funds");
                return;
            }

            send(paymentId, null, providerReference, "AUTHORIZED", null);
            Thread.sleep(properties.getCallbackDelay().toMillis());
            send(paymentId, null, providerReference, "CAPTURED", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** Refund outcomes are a single callback: the money either went back or it did not. */
    @Async
    public void scheduleRefundOutcome(
            UUID refundId, UUID paymentId, String providerReference, boolean declined) {
        try {
            Thread.sleep(properties.getCallbackDelay().toMillis());
            if (declined) {
                send(paymentId, refundId, providerReference, "REFUND_FAILED", "refund_rejected");
            } else {
                send(paymentId, refundId, providerReference, "REFUND_SUCCEEDED", null);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(
            UUID paymentId, UUID refundId, String providerReference, String outcome, String failureReason) {
        ProviderCallback callback = new ProviderCallback(
                properties.getName() + "-evt-" + UUID.randomUUID(),
                paymentId,
                refundId,
                properties.getName(),
                providerReference,
                outcome,
                failureReason,
                OffsetDateTime.now());

        try {
            String body = objectMapper.writeValueAsString(callback);
            long timestamp = Instant.now().getEpochSecond();
            restClient.post()
                    .uri(properties.getCallbackUrl() + "/" + properties.getName())
                    .contentType(MediaType.APPLICATION_JSON)
                    // Signed over the timestamp and the exact bytes sent, so the receiver can
                    // verify both who sent it and that it was sent now. A signature over the body
                    // alone would stay valid forever once captured.
                    .header(SIGNATURE_HEADER, sign(timestamp, body))
                    .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Sent {} callback for payment {}", outcome, paymentId);
        } catch (Exception exception) {
            // A real acquirer would retry on a schedule. Logging is enough for a simulator, and it
            // keeps a webhook outage from looking like a bank failure.
            log.warn("Callback delivery failed for payment {} ({})", paymentId, outcome, exception);
        }
    }

    private String sign(long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getSigningSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            String signedPayload = timestampSeconds + "." + body;
            return HexFormat.of().formatHex(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign callback", exception);
        }
    }
}
