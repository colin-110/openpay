package com.openpay.payment.infrastructure;

import com.openpay.payment.domain.FraudStatus;
import com.openpay.security.AdminTokenFilter;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Asks fraud-service whether a payment may proceed.
 *
 * <p>This call sits in the merchant's request path, so the timeouts are deliberately tight and the
 * behaviour when it fails is a configured decision rather than an accident — see
 * {@link #screen}.
 */
public class FraudScreeningClient {

    private static final Logger log = LoggerFactory.getLogger(FraudScreeningClient.class);

    private final RestClient restClient;
    private final String internalToken;
    private final boolean failOpen;

    public FraudScreeningClient(RestClient restClient, String internalToken, boolean failOpen) {
        this.restClient = restClient;
        this.internalToken = internalToken == null ? "" : internalToken;
        this.failOpen = failOpen;
    }

    /**
     * Screens a payment, or decides what to do when screening cannot be reached.
     *
     * <p>The fallback is {@code fail-open} by default, and that is a real trade rather than a
     * shrug. Failing closed means one unhealthy risk service stops every merchant on the platform
     * from taking money — an outage caused by the thing meant to prevent losses. Failing open means
     * a window where unscreened payments go through, which is a bounded, insurable cost. The payment
     * is recorded as {@link FraudStatus#UNSCREENED} rather than {@code ALLOWED} so that window is
     * visible afterwards instead of being indistinguishable from a clean pass.
     *
     * <p>A deployment that would rather stop taking payments sets {@code openpay.fraud.fail-open}
     * to false.
     */
    public ScreeningOutcome screen(
            UUID paymentId, UUID merchantId, long amount, String currency, String methodType) {
        try {
            ScreeningResponse response = restClient
                    .post()
                    .uri("/internal/fraud/checks")
                    .header(AdminTokenFilter.INTERNAL_TOKEN_HEADER, internalToken)
                    .body(new ScreeningPayload(paymentId, merchantId, amount, currency, methodType))
                    .retrieve()
                    .body(ScreeningResponse.class);

            if (response == null || response.outcome() == null) {
                return unavailable(paymentId, "fraud-service returned no decision");
            }
            return switch (response.outcome()) {
                case "ALLOW" -> new ScreeningOutcome(FraudStatus.ALLOWED, null, null);
                case "REVIEW" -> new ScreeningOutcome(FraudStatus.HELD, response.ruleName(), response.reason());
                case "BLOCK" -> new ScreeningOutcome(FraudStatus.BLOCKED, response.ruleName(), response.reason());
                // A decision this service does not understand is not a reason to guess. Treat it
                // the same as no answer at all, which the configured fallback then handles.
                default -> unavailable(paymentId, "unrecognised outcome " + response.outcome());
            };
        } catch (RestClientException exception) {
            log.warn("Screening unavailable for payment {}", paymentId, exception);
            return unavailable(paymentId, exception.getMessage());
        }
    }

    private ScreeningOutcome unavailable(UUID paymentId, String detail) {
        if (failOpen) {
            log.warn("Letting payment {} through unscreened: {}", paymentId, detail);
            return new ScreeningOutcome(FraudStatus.UNSCREENED, null, null);
        }
        throw new ScreeningUnavailableException(detail);
    }

    /** What the gate decided, flattened to what payment-service needs to do about it. */
    public record ScreeningOutcome(FraudStatus status, String ruleName, String reason) {
    }

    private record ScreeningPayload(
            UUID paymentId, UUID merchantId, long amount, String currency, String paymentMethodType) {
    }

    private record ScreeningResponse(String outcome, String ruleName, String reason) {
    }
}
