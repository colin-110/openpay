package com.openpay.merchant.api;

import java.util.UUID;

/**
 * Where to deliver a merchant's webhooks and how to sign them.
 *
 * <p>Carries a live secret, so this is only ever returned from an admin-gated internal endpoint,
 * never from the merchant-facing API.
 */
public record WebhookConfigResponse(
        UUID merchantId,
        String webhookUrl,
        String webhookSecret) {
}
