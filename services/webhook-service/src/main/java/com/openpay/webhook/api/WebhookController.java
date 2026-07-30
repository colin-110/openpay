package com.openpay.webhook.api;

import com.openpay.observability.CorrelationIdFilter;
import com.openpay.webhook.application.WebhookIngestService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound provider callbacks.
 *
 * <p>The body is taken as a raw String rather than a bound object because the signature is computed
 * over the exact bytes the provider sent. Deserialising first and re-serialising to verify would
 * compare a different byte sequence and fail for benign reasons like key order.
 */
@RestController
@RequestMapping("/internal/provider/webhooks")
public class WebhookController {

    private static final String SIGNATURE_HEADER = "X-Provider-Signature";

    private final WebhookIngestService ingestService;

    public WebhookController(WebhookIngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/{provider}")
    public ResponseEntity<Map<String, String>> receive(
            @PathVariable("provider") String provider,
            @RequestHeader(value = SIGNATURE_HEADER, required = false) String signature,
            @RequestBody String rawBody,
            HttpServletRequest request) {

        WebhookIngestService.IngestResult result =
                ingestService.ingest(provider, rawBody, signature, MDC.get(CorrelationIdFilter.MDC_KEY));

        return switch (result) {
            // 200 for a duplicate, not an error: the provider did nothing wrong by retrying, and
            // telling it otherwise would make it retry harder.
            case ACCEPTED, DUPLICATE -> ResponseEntity.ok(Map.of("status", result.name().toLowerCase()));
            case INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("status", "invalid_signature"));
            case MALFORMED -> ResponseEntity.badRequest().body(Map.of("status", "malformed"));
        };
    }
}
