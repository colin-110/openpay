package com.openpay.mockbank.callback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.mockbank.domain.BankProperties;
import java.nio.charset.StandardCharsets;
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
                send(paymentId, providerReference, "FAILED", "insufficient_funds");
                return;
            }

            send(paymentId, providerReference, "AUTHORIZED", null);
            Thread.sleep(properties.getCallbackDelay().toMillis());
            send(paymentId, providerReference, "CAPTURED", null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void send(UUID paymentId, String providerReference, String outcome, String failureReason) {
        ProviderCallback callback = new ProviderCallback(
                properties.getName() + "-evt-" + UUID.randomUUID(),
                paymentId,
                properties.getName(),
                providerReference,
                outcome,
                failureReason,
                OffsetDateTime.now());

        try {
            String body = objectMapper.writeValueAsString(callback);
            restClient.post()
                    .uri(properties.getCallbackUrl() + "/" + properties.getName())
                    .contentType(MediaType.APPLICATION_JSON)
                    // Signed over the exact bytes sent, so the receiver can verify authenticity.
                    .header(SIGNATURE_HEADER, sign(body))
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

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(
                    properties.getSigningSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Could not sign callback", exception);
        }
    }
}
