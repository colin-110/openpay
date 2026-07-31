package com.openpay.notification.application;

import com.openpay.notification.domain.WebhookDelivery;
import com.openpay.notification.domain.WebhookDeliveryRepository;
import com.openpay.notification.infrastructure.MerchantConfigClient;
import com.openpay.notification.infrastructure.MerchantWebhookConfig;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Delivers queued webhooks to merchants.
 *
 * <p>Merchants' endpoints are outside our control: they time out, return 500s, and disappear for
 * hours. So every attempt is bounded, every failure is recorded, and the delivery is retried on a
 * widening schedule rather than immediately, which would turn one slow merchant into a hot loop.
 */
@Component
public class WebhookDispatcher {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final String SIGNATURE_HEADER = "X-OpenPay-Signature";
    private static final String TIMESTAMP_HEADER = "X-OpenPay-Timestamp";
    private static final String EVENT_ID_HEADER = "X-OpenPay-Event-Id";
    private static final String EVENT_TYPE_HEADER = "X-OpenPay-Event-Type";

    private final WebhookDeliveryRepository deliveryRepository;
    private final MerchantConfigClient merchantConfigClient;
    private final NotificationProperties properties;
    private final BackoffPolicy backoffPolicy;
    private final RestClient restClient;

    public WebhookDispatcher(
            WebhookDeliveryRepository deliveryRepository,
            MerchantConfigClient merchantConfigClient,
            NotificationProperties properties) {
        this.deliveryRepository = deliveryRepository;
        this.merchantConfigClient = merchantConfigClient;
        this.properties = properties;
        this.backoffPolicy = new BackoffPolicy(properties.getInitialBackoff(), properties.getMaxBackoff());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString = "${openpay.notification.poll-interval-ms:1000}")
    @Transactional
    public void dispatchDue() {
        List<WebhookDelivery> due =
                deliveryRepository.claimDue(OffsetDateTime.now(), properties.getBatchSize());
        for (WebhookDelivery delivery : due) {
            attempt(delivery);
        }
    }

    private void attempt(WebhookDelivery delivery) {
        MerchantWebhookConfig config;
        try {
            config = merchantConfigClient.fetch(delivery.getMerchantId());
        } catch (RuntimeException exception) {
            fail(delivery, null, null, "could not read merchant config: " + exception.getMessage());
            return;
        }

        if (config == null || !config.isDeliverable()) {
            // No URL or no secret. Retrying will not help until the merchant is configured, but the
            // attempt counter still runs out, which stops us holding the row forever.
            fail(delivery, null, null, "merchant has no webhook url or signing secret");
            return;
        }

        long timestamp = Instant.now().getEpochSecond();
        String signature = WebhookSigner.sign(config.webhookSecret(), timestamp, delivery.getPayload());

        try {
            ResponseEntity<Void> response = restClient.post()
                    .uri(config.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(SIGNATURE_HEADER, signature)
                    .header(TIMESTAMP_HEADER, String.valueOf(timestamp))
                    // Lets the merchant deduplicate: at-least-once applies to them too.
                    .header(EVENT_ID_HEADER, delivery.getEventId().toString())
                    .header(EVENT_TYPE_HEADER, delivery.getEventType())
                    .body(delivery.getPayload())
                    .retrieve()
                    .onStatus(status -> true, (request, clientResponse) -> { })
                    .toBodilessEntity();

            if (response.getStatusCode().is2xxSuccessful()) {
                delivery.recordSuccess(config.webhookUrl(), response.getStatusCode().value());
                deliveryRepository.save(delivery);
                log.info("Delivered {} to merchant {} ({})",
                        delivery.getEventType(), delivery.getMerchantId(), response.getStatusCode());
                return;
            }

            fail(delivery, config.webhookUrl(), response.getStatusCode().value(),
                    "merchant returned " + response.getStatusCode());
        } catch (RuntimeException exception) {
            fail(delivery, config.webhookUrl(), null, exception.getMessage());
        }
    }

    private void fail(WebhookDelivery delivery, String url, Integer responseStatus, String error) {
        delivery.recordFailure(
                url, responseStatus, error,
                backoffPolicy.backoffAfter(delivery.getAttempts() + 1),
                properties.getMaxAttempts());
        deliveryRepository.save(delivery);

        log.warn("Delivery {} to merchant {} failed on attempt {}: {}",
                delivery.getEventType(), delivery.getMerchantId(), delivery.getAttempts(), error);
    }
}
