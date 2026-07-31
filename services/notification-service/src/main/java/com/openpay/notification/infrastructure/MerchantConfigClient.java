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
 */
public class MerchantConfigClient {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";

    private final RestClient restClient;
    private final String adminToken;

    public MerchantConfigClient(NotificationProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getMerchantBaseUrl())
                .requestFactory(factory)
                .build();
        this.adminToken = properties.getAdminToken();
    }

    public MerchantWebhookConfig fetch(UUID merchantId) {
        return restClient.get()
                .uri("/api/v1/merchants/{merchantId}/webhook-config", merchantId)
                .header(ADMIN_TOKEN_HEADER, adminToken)
                .retrieve()
                .body(MerchantWebhookConfig.class);
    }
}
