package com.openpay.payment.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.FraudCheckCompleted;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.payment.application.PaymentService;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Releases or fails a payment that screening held.
 *
 * <p>Consuming an event rather than having fraud-service call back is what makes an operator's
 * decision durable: the release survives payment-service being down at the moment the button is
 * clicked, and is retried until it lands.
 */
@Component
public class FraudDecisionListener {

    private final PaymentService paymentService;
    private final EventCodec eventCodec;

    public FraudDecisionListener(PaymentService paymentService, EventCodec eventCodec) {
        this.paymentService = paymentService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.FRAUD_CHECK_COMPLETED, groupId = "payment-service")
    public void onFraudCheckCompleted(String message) {
        EventEnvelope<FraudCheckCompleted> event = eventCodec.decode(message, FraudCheckCompleted.class);
        FraudCheckCompleted completed = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            paymentService.applyScreeningOutcome(
                    completed.paymentId(),
                    "ALLOW".equals(completed.outcome()),
                    completed.reason());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
