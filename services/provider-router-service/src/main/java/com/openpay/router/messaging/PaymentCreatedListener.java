package com.openpay.router.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.PaymentCreated;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.router.application.RoutingService;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentCreatedListener {

    private final RoutingService routingService;
    private final EventCodec eventCodec;

    public PaymentCreatedListener(RoutingService routingService, EventCodec eventCodec) {
        this.routingService = routingService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.PAYMENT_CREATED, groupId = "provider-router-service")
    public void onPaymentCreated(String message) {
        EventEnvelope<PaymentCreated> event = eventCodec.decode(message, PaymentCreated.class);
        PaymentCreated payment = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            routingService.route(
                    payment.paymentId(),
                    payment.merchantId(),
                    payment.amount(),
                    payment.currency(),
                    event.correlationId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
