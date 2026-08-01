package com.openpay.router.application;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.RefundCallbackReceived;
import com.openpay.router.domain.ProviderTransaction;
import com.openpay.router.domain.ProviderTransactionRepository;
import com.openpay.router.infrastructure.ProviderClient;
import com.openpay.router.infrastructure.ProviderUnavailableException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Sends a refund back to the acquirer that took the original payment.
 *
 * <p>There is no routing choice to make here, and that is the point. A payment can be tried
 * against several providers until one accepts it, but a refund has exactly one valid destination:
 * the provider holding the money. Sending it anywhere else would be asking a bank to return funds
 * it never received.
 */
@Service
public class RefundRoutingService {

    private static final Logger log = LoggerFactory.getLogger(RefundRoutingService.class);
    private static final String ACCEPTED = "ACCEPTED";

    private final RoutingRuleService routingRuleService;
    private final ProviderTransactionRepository transactionRepository;
    private final ProviderClient providerClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec eventCodec;

    public RefundRoutingService(
            RoutingRuleService routingRuleService,
            ProviderTransactionRepository transactionRepository,
            ProviderClient providerClient,
            KafkaTemplate<String, String> kafkaTemplate,
            EventCodec eventCodec) {
        this.routingRuleService = routingRuleService;
        this.transactionRepository = transactionRepository;
        this.providerClient = providerClient;
        this.kafkaTemplate = kafkaTemplate;
        this.eventCodec = eventCodec;
    }

    public void routeRefund(
            UUID refundId, UUID paymentId, long amount, String currency, String correlationId) {

        Optional<ProviderTransaction> accepted =
                transactionRepository.findFirstByPaymentIdAndStatusOrderByAttemptNoDesc(paymentId, ACCEPTED);

        if (accepted.isEmpty()) {
            // Nothing was ever captured through a provider, so there is nothing to reverse.
            failRefund(refundId, paymentId, "router",
                    "no accepted provider transaction for payment " + paymentId, correlationId);
            return;
        }

        ProviderTransaction original = accepted.get();
        String providerName = original.getProviderName();
        // Disabled rules still resolve. Taking an acquirer out of rotation stops new payments
        // going to it; it must not strand every refund against the payments it already took.
        Optional<String> baseUrl = routingRuleService.baseUrlFor(providerName);

        if (baseUrl.isEmpty()) {
            // The acquirer that took the payment is not in the routing table at all. Failing over
            // is not an option — a refund goes back to whoever holds the money — so this fails
            // loudly rather than going somewhere wrong.
            failRefund(refundId, paymentId, providerName,
                    "provider " + providerName + " is no longer in the routing table", correlationId);
            return;
        }

        try {
            providerClient.dispatchRefund(
                    providerName, baseUrl.get(), refundId, paymentId,
                    amount, currency, original.getProviderReference());
            log.info("Refund {} dispatched to {} against {}",
                    refundId, providerName, original.getProviderReference());
        } catch (ProviderUnavailableException exception) {
            log.warn("Refund {} could not be dispatched to {}", refundId, providerName, exception);
            failRefund(refundId, paymentId, providerName, exception.getMessage(), correlationId);
        }
    }

    /** Reported as a callback so payment-service has one path that completes a refund. */
    private void failRefund(
            UUID refundId, UUID paymentId, String providerName, String reason, String correlationId) {

        log.error("Refund {} failed: {}", refundId, reason);
        RefundCallbackReceived payload = new RefundCallbackReceived(
                refundId, paymentId, providerName,
                // Deterministic id so a redelivery of this failure deduplicates like a real one.
                "router-" + refundId,
                RefundCallbackReceived.RefundOutcome.FAILED, reason);

        EventEnvelope<RefundCallbackReceived> envelope = EventEnvelope.of(
                OpenPayTopics.REFUND_CALLBACK_RECEIVED, refundId.toString(), correlationId, payload);
        kafkaTemplate.send(
                OpenPayTopics.REFUND_CALLBACK_RECEIVED, refundId.toString(), eventCodec.encode(envelope));
    }
}
