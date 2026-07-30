package com.openpay.mockbank.api;

import com.openpay.mockbank.callback.CallbackSender;
import com.openpay.mockbank.domain.BankProperties;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/provider")
public class BankController {

    private static final Logger log = LoggerFactory.getLogger(BankController.class);

    private final BankProperties properties;
    private final CallbackSender callbackSender;

    public BankController(BankProperties properties, CallbackSender callbackSender) {
        this.properties = properties;
        this.callbackSender = callbackSender;
    }

    @PostMapping("/payments")
    public ResponseEntity<?> acceptPayment(@Valid @RequestBody ProviderPaymentRequest request)
            throws InterruptedException {

        if (properties.isUnavailable()) {
            log.info("{} is configured unavailable, refusing payment {}",
                    properties.getName(), request.paymentId());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "acquirer_unavailable", "provider", properties.getName()));
        }

        if (roll() < properties.getTimeoutRate()) {
            // Hangs rather than erroring: a stuck acquirer is what actually trips a circuit breaker,
            // and it is the case naive clients get wrong by waiting forever.
            log.info("{} simulating a hang for payment {}", properties.getName(), request.paymentId());
            Thread.sleep(properties.getHangDuration().toMillis());
        }

        Thread.sleep(properties.getLatency().toMillis());

        String providerReference = properties.getName() + "-" + java.util.UUID.randomUUID();
        boolean declined = roll() < properties.getDeclineRate();

        log.info("{} accepted payment {} as {} (will {})",
                properties.getName(), request.paymentId(), providerReference,
                declined ? "decline" : "authorise then capture");

        // Accepted now, outcome later: the whole point of the asynchronous provider flow.
        callbackSender.scheduleOutcome(request.paymentId(), providerReference, declined);

        return ResponseEntity.accepted().body(new ProviderPaymentResponse(
                properties.getName(), providerReference, "ACCEPTED"));
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "provider", properties.getName(),
                "unavailable", properties.isUnavailable(),
                "declineRate", properties.getDeclineRate(),
                "timeoutRate", properties.getTimeoutRate());
    }

    private double roll() {
        return ThreadLocalRandom.current().nextDouble();
    }
}
