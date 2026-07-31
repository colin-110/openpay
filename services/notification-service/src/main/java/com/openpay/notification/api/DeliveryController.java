package com.openpay.notification.api;

import com.openpay.notification.domain.WebhookDelivery;
import com.openpay.notification.domain.WebhookDeliveryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Operational visibility: what was sent, what failed, and why. */
@RestController
@RequestMapping("/api/v1/webhooks")
public class DeliveryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WebhookDeliveryRepository deliveryRepository;

    public DeliveryController(WebhookDeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @GetMapping("/deliveries")
    public List<Map<String, Object>> deliveries(
            @RequestParam(name = "merchantId", required = false) UUID merchantId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        return (merchantId == null
                ? deliveryRepository.findAllByOrderByCreatedAtDesc(pageable)
                : deliveryRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, pageable))
                .map(this::describe)
                .getContent();
    }

    private Map<String, Object> describe(WebhookDelivery delivery) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", delivery.getId());
        row.put("merchantId", delivery.getMerchantId());
        row.put("eventType", delivery.getEventType());
        row.put("status", delivery.getStatus());
        row.put("attempts", delivery.getAttempts());
        row.put("responseStatus", delivery.getResponseStatus());
        row.put("lastError", delivery.getLastError());
        row.put("nextAttemptAt", delivery.getNextAttemptAt());
        row.put("deliveredAt", delivery.getDeliveredAt());
        return row;
    }
}
