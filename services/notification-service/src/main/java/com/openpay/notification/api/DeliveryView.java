package com.openpay.notification.api;

import com.openpay.notification.domain.WebhookDelivery;
import java.util.LinkedHashMap;
import java.util.Map;

/** One shape for a delivery row, so the merchant view and the operator view cannot drift apart. */
final class DeliveryView {

    private DeliveryView() {
    }

    static Map<String, Object> describe(WebhookDelivery delivery) {
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
        row.put("createdAt", delivery.getCreatedAt());
        return row;
    }
}
