package com.openpay.payment.api;

import com.openpay.payment.application.PaymentResult;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.infrastructure.ProviderRouterClient;
import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/payments")
@Validated
public class PaymentController {

    private static final int MAX_PAGE_SIZE = 100;

    private final PaymentService paymentService;
    private final ProviderRouterClient providerRouterClient;

    public PaymentController(PaymentService paymentService, ProviderRouterClient providerRouterClient) {
        this.paymentService = paymentService;
        this.providerRouterClient = providerRouterClient;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> createPayment(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) {

        PaymentResult result = paymentService.createPayment(principal.merchantId(), idempotencyKey, request);

        if (!result.created()) {
            // An idempotent replay did not create anything, so 201 would be a lie.
            return ResponseEntity.ok(result.payment());
        }

        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.payment().id())
                .toUri();
        return ResponseEntity.created(location).body(result.payment());
    }

    @GetMapping("/{paymentId}")
    public PaymentResponse getPayment(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @PathVariable("paymentId") UUID paymentId) {
        return paymentService.getPayment(principal.merchantId(), paymentId);
    }

    /**
     * What was tried at the acquirers for this payment, in order.
     *
     * <p>Fetching the payment first is the authorisation check: it throws for a payment belonging
     * to someone else, so the router is only ever asked about payments this caller can already see.
     */
    @GetMapping("/{paymentId}/attempts")
    public List<PaymentAttemptView> attempts(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @PathVariable("paymentId") UUID paymentId) {
        paymentService.getPayment(principal.merchantId(), paymentId);
        return providerRouterClient.attemptsFor(paymentId);
    }

    @GetMapping
    public PagedResponse<PaymentResponse> listPayments(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) PaymentStatus status) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return paymentService.listPayments(principal.merchantId(), status, pageable);
    }
}
