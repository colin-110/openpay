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

/**
 * Delivery history across every merchant, for an operator.
 *
 * <p>Split from the merchant-facing controller because the merchant filter takes its scope from
 * the credential while this one takes it from a query parameter. Leaving both behaviours on one
 * endpoint is how a merchant-authenticated caller ends up reading someone else's deliveries by
 * passing a different id.
 */
@RestController
@RequestMapping("/internal/webhooks")
public class DeliveryOperationsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WebhookDeliveryRepository deliveryRepository;

    public DeliveryOperationsController(WebhookDeliveryRepository deliveryRepository) {
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
                .map(DeliveryView::describe)
                .getContent();
    }
}
