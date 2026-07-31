package com.openpay.notification.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.notification.domain.WebhookDelivery;
import com.openpay.notification.domain.WebhookDeliveryRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns a domain event into exactly one queued webhook. */
@Service
public class DeliveryQueue {

    private static final Logger log = LoggerFactory.getLogger(DeliveryQueue.class);

    private final WebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;

    public DeliveryQueue(WebhookDeliveryRepository deliveryRepository, ObjectMapper objectMapper) {
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Queues a webhook, ignoring a redelivery of the same source event.
     *
     * <p>Enforced by a unique constraint rather than a lookup. Duplicating here would send the
     * merchant the same notification twice, which is more visible than most duplication bugs
     * because the merchant is the one who sees it.
     */
    @Transactional
    public Optional<WebhookDelivery> enqueue(UUID merchantId, UUID eventId, String eventType, Object payload) {
        Optional<WebhookDelivery> existing = deliveryRepository.findByEventId(eventId);
        if (existing.isPresent()) {
            log.debug("Event {} is already queued for delivery", eventId);
            return existing;
        }

        try {
            WebhookDelivery delivery = deliveryRepository.saveAndFlush(new WebhookDelivery(
                    merchantId, eventId, eventType, objectMapper.writeValueAsString(payload)));
            log.info("Queued {} for merchant {}", eventType, merchantId);
            return Optional.of(delivery);
        } catch (DataIntegrityViolationException exception) {
            return deliveryRepository.findByEventId(eventId);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not serialise webhook payload", exception);
        }
    }
}
