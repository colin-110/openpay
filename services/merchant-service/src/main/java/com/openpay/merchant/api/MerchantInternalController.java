package com.openpay.merchant.api;

import com.openpay.merchant.application.MerchantService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service reads. Guarded by the internal service token rather than the admin token.
 *
 * <p>notification-service needs one thing from this service on every delivery attempt: the target
 * URL and the live signing secret for a merchant. Before this existed it held the platform admin
 * token to get it — the same credential that onboards merchants and issues API keys — which meant
 * the service most exposed to the outside world (the one making arbitrary outbound HTTP calls) also
 * held the keys to the platform. A credential scoped to exactly this read removes that.
 */
@RestController
@RequestMapping("/internal/merchants")
public class MerchantInternalController {

    private final MerchantService merchantService;

    public MerchantInternalController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    @GetMapping("/{merchantId}/webhook-config")
    public WebhookConfigResponse webhookConfig(@PathVariable("merchantId") UUID merchantId) {
        return merchantService.getWebhookConfig(merchantId);
    }
}
