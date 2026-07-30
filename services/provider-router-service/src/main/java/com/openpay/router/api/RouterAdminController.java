package com.openpay.router.api;

import com.openpay.router.application.RoutingService;
import com.openpay.router.domain.ProviderTransactionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational visibility: which providers are in rotation, and what was tried for a payment. */
@RestController
@RequestMapping("/internal/router")
public class RouterAdminController {

    private final RoutingService routingService;
    private final ProviderTransactionRepository transactionRepository;

    public RouterAdminController(
            RoutingService routingService, ProviderTransactionRepository transactionRepository) {
        this.routingService = routingService;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/providers")
    public Map<String, String> providers() {
        return routingService.breakerStates().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, entry -> entry.getValue().name(),
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    @GetMapping("/payments/{paymentId}/attempts")
    public List<Map<String, Object>> attempts(@PathVariable("paymentId") UUID paymentId) {
        return transactionRepository.findByPaymentIdOrderByAttemptNoAsc(paymentId).stream()
                .map(txn -> {
                    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("attemptNo", txn.getAttemptNo());
                    row.put("provider", txn.getProviderName());
                    row.put("status", txn.getStatus());
                    row.put("providerReference", txn.getProviderReference());
                    row.put("failureReason", txn.getFailureReason());
                    return row;
                })
                .toList();
    }
}
