package com.openpay.payment.api;

import com.openpay.payment.application.RefundResult;
import com.openpay.payment.application.RefundService;
import com.openpay.payment.domain.RefundStatus;
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
@RequestMapping("/api/v1/refunds")
@Validated
public class RefundController {

    private static final int MAX_PAGE_SIZE = 100;

    private final RefundService refundService;

    public RefundController(RefundService refundService) {
        this.refundService = refundService;
    }

    @PostMapping
    public ResponseEntity<RefundResponse> createRefund(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 255) String idempotencyKey,
            @Valid @RequestBody CreateRefundRequest request) {

        RefundResult result = refundService.createRefund(principal.merchantId(), idempotencyKey, request);

        if (!result.created()) {
            return ResponseEntity.ok(result.refund());
        }
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(result.refund().id())
                .toUri();
        return ResponseEntity.created(location).body(result.refund());
    }

    @GetMapping("/{refundId}")
    public RefundResponse getRefund(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @PathVariable("refundId") UUID refundId) {
        return refundService.getRefund(principal.merchantId(), refundId);
    }

    /** Every refund against one payment, oldest first: a payment is refunded in instalments. */
    @GetMapping(params = "paymentId")
    public List<RefundResponse> listForPayment(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestParam("paymentId") UUID paymentId) {
        return refundService.refundsForPayment(principal.merchantId(), paymentId);
    }

    /**
     * Every refund the merchant has made, newest first. Separate from the by-payment listing above
     * because the two answer different questions and page differently.
     */
    @GetMapping
    public PagedResponse<RefundResponse> listRefunds(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "status", required = false) RefundStatus status) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        return refundService.listRefunds(principal.merchantId(), status, pageable);
    }
}
