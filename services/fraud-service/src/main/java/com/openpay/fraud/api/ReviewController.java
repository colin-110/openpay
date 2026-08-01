package com.openpay.fraud.api;

import com.openpay.fraud.application.FraudService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue, on the operator tier.
 *
 * <p>Resolving a review releases or refuses a payment, which is a decision about money — but it does
 * not mint a credential, so it sits with settlement runs and the ledger rather than with merchant
 * onboarding and key issuance.
 */
@RestController
@RequestMapping("/internal/fraud/reviews")
public class ReviewController {

    private final FraudService fraudService;

    public ReviewController(FraudService fraudService) {
        this.fraudService = fraudService;
    }

    @GetMapping
    public List<DecisionView> openReviews(
            @RequestParam(name = "merchantId", required = false) UUID merchantId) {
        return fraudService.openReviews(merchantId).stream().map(DecisionView::of).toList();
    }

    @PostMapping("/{paymentId}/resolve")
    public DecisionView resolve(
            @PathVariable("paymentId") UUID paymentId,
            @Valid @RequestBody ResolveReviewRequest request) {
        return DecisionView.of(
                fraudService.resolveReview(paymentId, request.outcome(), request.resolvedBy()));
    }
}
