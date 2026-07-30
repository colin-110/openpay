package com.openpay.webhook.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.ProviderCallbackReceived;
import com.openpay.webhook.domain.ProviderWebhookEvent;
import com.openpay.webhook.domain.ProviderWebhookEventRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Turns a verified provider callback into an internal domain event.
 *
 * <p>Two things have to be true before anything is published: the signature checks out, and we
 * have not seen this provider event before.
 */
@Service
public class WebhookIngestService {

    private static final Logger log = LoggerFactory.getLogger(WebhookIngestService.class);

    private final ProviderWebhookEventRepository repository;
    private final SignatureVerifier signatureVerifier;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec eventCodec;

    public WebhookIngestService(
            ProviderWebhookEventRepository repository,
            SignatureVerifier signatureVerifier,
            KafkaTemplate<String, String> kafkaTemplate,
            EventCodec eventCodec) {
        this.repository = repository;
        this.signatureVerifier = signatureVerifier;
        this.kafkaTemplate = kafkaTemplate;
        this.eventCodec = eventCodec;
    }

    public IngestResult ingest(String providerName, String rawBody, String signature, String correlationId) {
        if (!signatureVerifier.verify(providerName, rawBody, signature)) {
            log.warn("Rejected callback from {} with an invalid signature", providerName);
            return IngestResult.INVALID_SIGNATURE;
        }

        JsonNode body;
        try {
            body = eventCodec.objectMapper().readTree(rawBody);
        } catch (Exception exception) {
            log.warn("Rejected unparseable callback from {}", providerName, exception);
            return IngestResult.MALFORMED;
        }

        String providerEventId = text(body, "eventId");
        String outcome = text(body, "outcome");
        String paymentIdText = text(body, "paymentId");
        if (providerEventId == null || outcome == null || paymentIdText == null) {
            return IngestResult.MALFORMED;
        }

        UUID paymentId;
        try {
            paymentId = UUID.fromString(paymentIdText);
        } catch (IllegalArgumentException exception) {
            return IngestResult.MALFORMED;
        }

        if (repository.findByProviderNameAndProviderEventId(providerName, providerEventId).isPresent()) {
            log.info("Ignoring duplicate callback {} from {}", providerEventId, providerName);
            return IngestResult.DUPLICATE;
        }

        try {
            // saveAndFlush carries its own transaction, and flushing here is what surfaces the
            // unique-constraint violation now rather than at some later commit.
            repository.saveAndFlush(new ProviderWebhookEvent(
                    providerName, providerEventId, paymentId, text(body, "providerReference"),
                    outcome, true, rawBody));
        } catch (DataIntegrityViolationException exception) {
            // Two deliveries of the same callback arrived at once; the unique constraint settled it.
            log.info("Concurrent duplicate callback {} from {}", providerEventId, providerName);
            return IngestResult.DUPLICATE;
        }

        publish(providerName, providerEventId, paymentId, text(body, "providerReference"),
                outcome, text(body, "failureReason"), correlationId);
        return IngestResult.ACCEPTED;
    }

    private void publish(
            String providerName,
            String providerEventId,
            UUID paymentId,
            String providerReference,
            String outcome,
            String failureReason,
            String correlationId) {

        ProviderCallbackReceived payload = new ProviderCallbackReceived(
                paymentId,
                providerName,
                providerReference,
                providerEventId,
                ProviderCallbackReceived.ProviderOutcome.valueOf(outcome),
                failureReason);

        EventEnvelope<ProviderCallbackReceived> envelope = EventEnvelope.of(
                OpenPayTopics.PROVIDER_CALLBACK_RECEIVED, paymentId.toString(), correlationId, payload);

        kafkaTemplate.send(
                OpenPayTopics.PROVIDER_CALLBACK_RECEIVED, paymentId.toString(), eventCodec.encode(envelope));
        log.info("Published {} callback for payment {} from {}", outcome, paymentId, providerName);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public enum IngestResult {
        ACCEPTED,
        DUPLICATE,
        INVALID_SIGNATURE,
        MALFORMED
    }
}
