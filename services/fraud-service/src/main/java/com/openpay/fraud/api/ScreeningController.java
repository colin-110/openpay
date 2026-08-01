package com.openpay.fraud.api;

import com.openpay.fraud.application.FraudService;
import com.openpay.fraud.application.ScreeningRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gate itself, on the internal tier.
 *
 * <p>Never merchant-facing. A merchant that could call this could probe the thresholds by binary
 * search, which is most of the work of evading them.
 */
@RestController
@RequestMapping("/internal/fraud/checks")
public class ScreeningController {

    private final FraudService fraudService;

    public ScreeningController(FraudService fraudService) {
        this.fraudService = fraudService;
    }

    @PostMapping
    public ScreenPaymentResponse screen(@Valid @RequestBody ScreenPaymentRequest request) {
        return ScreenPaymentResponse.of(fraudService.screen(new ScreeningRequest(
                request.paymentId(),
                request.merchantId(),
                request.amount(),
                request.currency().toUpperCase(),
                request.paymentMethodType())));
    }
}
