package com.openpay.payment.infrastructure;

import com.openpay.payment.api.PaymentAttemptView;
import com.openpay.security.AdminTokenFilter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads a payment's acquirer attempts from provider-router-service, which owns them.
 *
 * <p>The alternative was for payment-service to keep its own copy by consuming routing events. That
 * would decouple the read, at the cost of a second table saying the same thing and a window where
 * the two disagree. A synchronous read is the honest trade here: the data has one owner, and the
 * only cost is that attempt history is unavailable while the router is down — which is exactly what
 * this reports, rather than pretending a payment had no attempts.
 */
public class ProviderRouterClient {

    private static final Logger log = LoggerFactory.getLogger(ProviderRouterClient.class);

    private final RestClient restClient;
    private final String internalToken;

    public ProviderRouterClient(RestClient restClient, String internalToken) {
        this.restClient = restClient;
        this.internalToken = internalToken == null ? "" : internalToken;
    }

    /**
     * The merchant is passed as well as the payment, so the router can scope the query itself.
     * The caller has already established ownership; sending it again means a leaked payment id
     * alone does not read another merchant's routing history.
     */
    public List<PaymentAttemptView> attemptsFor(UUID paymentId, UUID merchantId) {
        try {
            List<PaymentAttemptView> attempts = restClient
                    .get()
                    .uri(builder -> builder
                            .path("/internal/router/payments/{paymentId}/attempts")
                            .queryParam("merchantId", merchantId)
                            .build(paymentId))
                    .header(AdminTokenFilter.INTERNAL_TOKEN_HEADER, internalToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<List<PaymentAttemptView>>() {});
            return attempts == null ? List.of() : attempts;
        } catch (RestClientException exception) {
            log.warn("Could not read routing attempts for payment {}", paymentId, exception);
            throw new AttemptsUnavailableException(exception);
        }
    }
}
