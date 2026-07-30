package com.openpay.router.application;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.ProviderCallbackReceived;
import com.openpay.events.payload.ProviderDispatched;
import com.openpay.router.domain.ProviderTransaction;
import com.openpay.router.domain.ProviderTransactionRepository;
import com.openpay.router.infrastructure.ProviderClient;
import com.openpay.router.infrastructure.ProviderUnavailableException;
import java.util.Comparator;
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
 * <p>Providers are tried in priority order, skipping any whose circuit breaker is open. Each
 * attempt is recorded before the call is made, so a payment that ends up on the second acquirer
 * still shows what was tried first and why it was abandoned.
 */
@Service
public class RoutingService {

    private static final Logger log = LoggerFactory.getLogger(RoutingService.class);

    private final RouterProperties properties;
    private final ProviderTransactionRepository transactionRepository;
    private final ProviderClient providerClient;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final EventCodec eventCodec;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    public RoutingService(
            RouterProperties properties,
            ProviderTransactionRepository transactionRepository,
            ProviderClient providerClient,
            KafkaTemplate<String, String> kafkaTemplate,
            EventCodec eventCodec) {
        this.properties = properties;
        this.transactionRepository = transactionRepository;
        this.providerClient = providerClient;
        this.kafkaTemplate = kafkaTemplate;
        this.eventCodec = eventCodec;
    }

    public void route(UUID paymentId, UUID merchantId, long amount, String currency, String correlationId) {
        // A redelivered payment.created must not produce a second charge attempt.
        if (transactionRepository.existsByPaymentId(paymentId)) {
            log.info("Payment {} has already been routed, ignoring redelivery", paymentId);
            return;
        }

        List<RouterProperties.Provider> candidates = properties.getProviders().stream()
                .filter(RouterProperties.Provider::isEnabled)
                .sorted(Comparator.comparingInt(RouterProperties.Provider::getPriority))
                .toList();

        if (candidates.isEmpty()) {
            failPayment(paymentId, merchantId, "no providers configured", correlationId);
            return;
        }

        int attemptNo = 0;
        for (RouterProperties.Provider provider : candidates) {
            CircuitBreaker breaker = breakerFor(provider.getName());
            if (!breaker.allowsRequest()) {
                log.warn("Skipping {} for payment {}: circuit breaker is {}",
                        provider.getName(), paymentId, breaker.state());
                continue;
            }

            attemptNo++;
            ProviderTransaction attempt = transactionRepository.saveAndFlush(new ProviderTransaction(
                    paymentId, merchantId, provider.getName(), attemptNo, amount, currency));

            try {
                String providerReference = providerClient.dispatch(
                        provider.getName(), provider.getBaseUrl(), paymentId, amount, currency);

                attempt.markAccepted(providerReference);
                transactionRepository.save(attempt);
                breaker.recordSuccess();

                publishDispatched(paymentId, merchantId, provider.getName(),
                        providerReference, attemptNo, correlationId);
                log.info("Payment {} dispatched to {} as {} on attempt {}",
                        paymentId, provider.getName(), providerReference, attemptNo);
                return;

            } catch (ProviderUnavailableException exception) {
                attempt.markFailed(exception.getMessage());
                transactionRepository.save(attempt);
                breaker.recordFailure();
                log.warn("Attempt {} on {} failed for payment {} ({} consecutive failures), trying next",
                        attemptNo, provider.getName(), paymentId, breaker.consecutiveFailures());
            }
        }

        // Failing over is only worth doing if running out of providers is itself an outcome.
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

    /** Exposed for the admin endpoint and tests. */
    public Map<String, CircuitBreaker.State> breakerStates() {
        Map<String, CircuitBreaker.State> states = new java.util.LinkedHashMap<>();
        properties.getProviders().forEach(provider ->
                states.put(provider.getName(), breakerFor(provider.getName()).state()));
        return states;
    }
}
