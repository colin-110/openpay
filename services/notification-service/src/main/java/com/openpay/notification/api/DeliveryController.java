package com.openpay.notification.api;

import com.openpay.notification.domain.WebhookDeliveryRepository;
import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the platform sent to this merchant, what failed, and why.
 *
 * <p>Scope comes from the credential and cannot be overridden by a parameter. The operator view
 * that looks across merchants lives on {@code /internal/webhooks} instead.
 */
@RestController
@RequestMapping("/api/v1/webhooks")
public class DeliveryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WebhookDeliveryRepository deliveryRepository;

    public DeliveryController(WebhookDeliveryRepository deliveryRepository) {
        this.deliveryRepository = deliveryRepository;
    }

    @GetMapping("/deliveries")
    public Map<String, Object> deliveries(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        PageRequest pageable = PageRequest.of(
                Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));

        Page<Map<String, Object>> found = deliveryRepository
                .findByMerchantIdOrderByCreatedAtDesc(principal.merchantId(), pageable)
                .map(DeliveryView::describe);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", found.getContent());
        body.put("page", found.getNumber());
        body.put("size", found.getSize());
        body.put("totalItems", found.getTotalElements());
        body.put("totalPages", found.getTotalPages());
        return body;
    }
}
