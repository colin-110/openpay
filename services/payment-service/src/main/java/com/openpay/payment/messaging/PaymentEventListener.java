package com.openpay.payment.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.ProviderCallbackReceived;
import com.openpay.events.payload.ProviderDispatched;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.PaymentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Drives the payment lifecycle from what the rest of the platform reports.
 *
 * <p>Merchants cannot move their own payments forward; only a routing decision or a verified
 * provider callback can. That is the whole point of taking the status endpoint off the public API.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentService paymentService;
    private final EventCodec eventCodec;

    public PaymentEventListener(PaymentService paymentService, EventCodec eventCodec) {
        this.paymentService = paymentService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.PAYMENT_PROVIDER_DISPATCHED, groupId = "payment-service")
    public void onProviderDispatched(String message) {
        EventEnvelope<ProviderDispatched> event = eventCodec.decode(message, ProviderDispatched.class);
        withCorrelation(event.correlationId(), () -> {
            ProviderDispatched dispatched = event.payload();
            paymentService.applyTransition(
                    dispatched.paymentId(),
                    PaymentStatus.PENDING_PROVIDER,
                    "dispatched to " + dispatched.providerName() + " attempt " + dispatched.attemptNo());
        });
    }

    @KafkaListener(topics = OpenPayTopics.PROVIDER_CALLBACK_RECEIVED, groupId = "payment-service")
    public void onProviderCallback(String message) {
        EventEnvelope<ProviderCallbackReceived> event =
                eventCodec.decode(message, ProviderCallbackReceived.class);
        withCorrelation(event.correlationId(), () -> {
            ProviderCallbackReceived callback = event.payload();
            PaymentStatus target = switch (callback.outcome()) {
                case AUTHORIZED -> PaymentStatus.AUTHORIZED;
                case CAPTURED -> PaymentStatus.CAPTURED;
                case FAILED -> PaymentStatus.FAILED;
            };

            String reason = callback.outcome() == ProviderCallbackReceived.ProviderOutcome.FAILED
                    ? callback.providerName() + " declined: " + callback.failureReason()
                    : callback.providerName() + " reported " + callback.outcome();

            paymentService.applyTransition(callback.paymentId(), target, reason);
        });
    }

    /** Keeps the originating request's correlation id on the log lines this consumer writes. */
    private void withCorrelation(String correlationId, Runnable action) {
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.error("Failed to handle event", exception);
            throw exception;
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
