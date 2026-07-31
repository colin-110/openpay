package com.openpay.notification.infrastructure;

import java.util.UUID;

public record MerchantWebhookConfig(UUID merchantId, String webhookUrl, String webhookSecret) {

    /** A merchant with no URL or no secret cannot be delivered to. */
    public boolean isDeliverable() {
        return webhookUrl != null && !webhookUrl.isBlank()
                && webhookSecret != null && !webhookSecret.isBlank();
    }
}
