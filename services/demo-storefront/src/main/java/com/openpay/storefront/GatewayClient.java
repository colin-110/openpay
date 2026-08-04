package com.openpay.storefront;

import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * The shop's side of the integration: create a payment, then ask how it is getting on.
 *
 * <p>Two calls, which is genuinely all a merchant needs. Both carry the shop's API key, and the key
 * never leaves this process.
 */
@Component
public class GatewayClient {

    private final RestClient restClient;
    private final StorefrontProperties properties;

    public GatewayClient(StorefrontProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build());
        factory.setReadTimeout(properties.getReadTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getGatewayBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * @param idempotencyKey supplied by the caller, not generated here, so that a customer who
     *     double-clicks Pay gets the payment they already made rather than a second one. This is
     *     the whole reason the header exists, and it costs one line to honour.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> createPayment(
            long amountMinorUnits, String currency, String idempotencyKey, String token) {

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amountMinorUnits);
        body.put("currency", currency);
        if (token != null && !token.isBlank()) {
            // Only the token. The network and last four come back from what the platform actually
            // tokenised, so there is nothing useful this shop could add here and nothing it could
            // misrepresent by trying.
            body.put("paymentMethod", Map.of("token", token));
        }

        return restClient.post()
                .uri("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Api-Key", properties.getApiKey())
                .header("Idempotency-Key", idempotencyKey)
                .body(body)
                .retrieve()
                .body(Map.class);
    }

    /**
     * Reads one payment back.
     *
     * <p>The gateway scopes every read to the merchant the credential belongs to, so this shop can
     * only ever see its own payments — a payment id from somewhere else reads as not-found rather
     * than as someone else's money.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getPayment(UUID paymentId) {
        try {
            return restClient.get()
                    .uri("/api/v1/payments/{id}", paymentId)
                    .header("X-Api-Key", properties.getApiKey())
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException notFound) {
            if (notFound.getStatusCode().value() == 404) {
                return null;
            }
            throw notFound;
        }
    }
}
