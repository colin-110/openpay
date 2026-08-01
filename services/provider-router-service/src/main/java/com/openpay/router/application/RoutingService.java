package com.openpay.router.application;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.ProviderCallbackReceived;
import com.openpay.events.payload.ProviderDispatched;
import com.openpay.router.domain.ProviderTransaction;
import com.openpay.router.domain.ProviderTransactionRepository;
import com.openpay.router.domain.RoutingRule;
import com.openpay.router.infrastructure.ProviderClient;
import com.openpay.router.infrastructure.ProviderUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Chooses an acquirer for each payment and fails over when one will not take it.
 *
 * <p>Candidates come from the {@code provider_routing_rules} table, which is what makes taking an
 * acquirer out of rotation an operator action rather than a deployment. They are tried in priority
 * order, skipping any whose circuit breaker is open. Each attempt is recorded before the call is
 * made, so a payment that ends up on the second acquirer still shows what was tried first and why
 * it was abandoned.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final RouterProperties properties;
    private final RoutingRuleService routingRuleService;
    private final ProviderTransactionRepository transactionRepository;
    private final ProviderClient providerClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec eventCodec;
    private final RouterMetrics metrics;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public RoutingService(
            RouterProperties properties,
            RoutingRuleService routingRuleService,
            ProviderTransactionRepository transactionRepository,
            ProviderClient providerClient,
            KafkaTemplate<String, String> kafkaTemplate,
            EventCodec eventCodec,
            RouterMetrics metrics) {
        this.properties = properties;
        this.routingRuleService = routingRuleService;
        this.transactionRepository = transactionRepository;
        this.providerClient = providerClient;
        this.kafkaTemplate = kafkaTemplate;
        this.eventCodec = eventCodec;
        this.metrics = metrics;
    }

    public void route(UUID paymentId, UUID merchantId, long amount, String currency, String correlationId) {
        // A redelivered payment.created must not produce a second charge attempt.
        if (transactionRepository.existsByPaymentId(paymentId)) {
            log.info("Payment {} has already been routed, ignoring redelivery", paymentId);
            return;
        }

        List<RoutingRule> candidates = routingRuleService.candidatesFor(merchantId, currency, amount);

        if (candidates.isEmpty()) {
            // Distinguished from "every acquirer refused it": no rule matched at all, which is a
            // configuration problem and not an acquirer problem, and the two want different people.
            metrics.routingExhausted("no_matching_rule");
            failPayment(paymentId, merchantId, "no routing rule matches this payment", correlationId);
            return;
        }

        int attemptNo = 0;
        for (RoutingRule rule : candidates) {
            CircuitBreaker breaker = breakerFor(rule.getProviderName());
            if (!breaker.allowsRequest()) {
                log.warn("Skipping {} for payment {}: circuit breaker is {}",
                        rule.getProviderName(), paymentId, breaker.state());
                metrics.skippedByBreaker(rule.getProviderName());
                continue;
            }

            attemptNo++;
            ProviderTransaction attempt = transactionRepository.saveAndFlush(new ProviderTransaction(
                    paymentId, merchantId, rule.getProviderName(), attemptNo, amount, currency));

            try {
                String providerReference = providerClient.dispatch(
                        rule.getProviderName(), rule.getBaseUrl(), paymentId, amount, currency);

                attempt.markAccepted(providerReference);
                transactionRepository.save(attempt);
                breaker.recordSuccess();
                metrics.attempt(rule.getProviderName(), "accepted");

                publishDispatched(paymentId, merchantId, rule.getProviderName(),
                        providerReference, attemptNo, correlationId);
                log.info("Payment {} dispatched to {} as {} on attempt {}",
                        paymentId, rule.getProviderName(), providerReference, attemptNo);
                return;

            } catch (ProviderUnavailableException exception) {
                attempt.markFailed(exception.getMessage());
                transactionRepository.save(attempt);
                breaker.recordFailure();
                metrics.attempt(rule.getProviderName(), "failed");
                log.warn("Attempt {} on {} failed for payment {} ({} consecutive failures), trying next",
                        attemptNo, rule.getProviderName(), paymentId, breaker.consecutiveFailures());
            }
        }

        // Failing over is only worth doing if running out of providers is itself an outcome.
        // Tagged apart from no_matching_rule: one is an acquirer problem and the other is a
        // configuration problem, and they want different people woken up.
        metrics.routingExhausted("all_providers_exhausted");
        failPayment(paymentId, merchantId, "all providers exhausted", correlationId);
    }

    private void failPayment(UUID paymentId, UUID merchantId, String reason, String correlationId) {
        log.error("Could not route payment {}: {}", paymentId, reason);

        // Reported as a provider callback so payment-service has exactly one path that moves a
        // payment to a terminal state, whether the answer came from an acquirer or from us.
        ProviderCallbackReceived payload = new ProviderCallbackReceived(
                paymentId,
                "router",
                null,
                "router-" + paymentId,
                ProviderCallbackReceived.ProviderOutcome.FAILED,
                reason);

        send(OpenPayTopics.PROVIDER_CALLBACK_RECEIVED, paymentId, payload, correlationId);
    }

    private void publishDispatched(
            UUID paymentId, UUID merchantId, String providerName,
            String providerReference, int attemptNo, String correlationId) {
        send(OpenPayTopics.PAYMENT_PROVIDER_DISPATCHED, paymentId,
                new ProviderDispatched(paymentId, merchantId, providerName, providerReference, attemptNo),
                correlationId);
    }

    private void send(String topic, UUID paymentId, Object payload, String correlationId) {
        EventEnvelope<Object> envelope =
                EventEnvelope.of(topic, paymentId.toString(), correlationId, payload);
        kafkaTemplate.send(topic, paymentId.toString(), eventCodec.encode(envelope));
    }

    private CircuitBreaker breakerFor(String providerName) {
        return breakers.computeIfAbsent(providerName, name -> new CircuitBreaker(
                name, properties.getFailureThreshold(), properties.getBreakerOpenDuration()));
    }

    /**
     * Exposed for the admin endpoint and tests.
     *
     * <p>Keyed off the rules rather than the configuration, so an acquirer added to the table after
     * startup shows its breaker state without a restart. Distinct providers only: the same acquirer
     * can appear in several rules and has one breaker.
     */
    public Map<String, CircuitBreaker.State> breakerStates() {
        Map<String, CircuitBreaker.State> states = new java.util.LinkedHashMap<>();
        routingRuleService.listRules().stream()
                .map(RoutingRule::getProviderName)
                .distinct()
                .forEach(name -> states.put(name, breakerFor(name).state()));
        return states;
    }
}
