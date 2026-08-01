package com.openpay.fraud.api;

import com.openpay.fraud.application.FraudService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Why one payment was judged the way it was. Operator tier, same as the queue. */
@RestController
@RequestMapping("/internal/fraud/decisions")
public class DecisionController {

    private final FraudService fraudService;

    public DecisionController(FraudService fraudService) {
        this.fraudService = fraudService;
    }

    @GetMapping("/{paymentId}")
    public DecisionView decision(@PathVariable("paymentId") UUID paymentId) {
        return DecisionView.of(fraudService.decisionFor(paymentId));
    }
}
