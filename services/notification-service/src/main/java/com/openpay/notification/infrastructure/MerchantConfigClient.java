package com.openpay.notification.infrastructure;

import com.openpay.notification.application.NotificationProperties;
import java.util.UUID;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Reads a merchant's delivery configuration.
 *
 * <p>Fetched per dispatch rather than cached, so rotating a secret or changing a URL takes effect
 * on the next attempt instead of after a cache expiry. If merchant-service is unreachable the
 * delivery simply fails and retries, which is the behaviour we already need for a merchant whose
 * own endpoint is down.
 *
 * <p>Authenticated with the service token, not the admin token. This is the service that makes
 * arbitrary outbound HTTP calls to merchant-controlled URLs, so it is the last place that should
 * hold a credential that also onboards merchants and issues API keys.
 */
public class MerchantConfigClient {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final RestClient restClient;
    private final String internalToken;

    public MerchantConfigClient(NotificationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getMerchantBaseUrl())
                .requestFactory(factory)
                .build();
        this.internalToken = properties.getInternalToken();
    }

    public MerchantWebhookConfig fetch(UUID merchantId) {
        return restClient.get()
                .uri("/internal/merchants/{merchantId}/webhook-config", merchantId)
                .header(INTERNAL_TOKEN_HEADER, internalToken)
                .retrieve()
                .body(MerchantWebhookConfig.class);
    }
}
