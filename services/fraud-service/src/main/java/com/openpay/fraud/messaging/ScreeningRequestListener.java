package com.openpay.fraud.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.FraudCheckRequested;
import com.openpay.fraud.application.FraudService;
import com.openpay.fraud.application.ScreeningRequest;
import com.openpay.observability.CorrelationIdFilter;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Screens a payment that was accepted before it was screened.
 *
 * <p>Only used when payment-service runs with {@code openpay.fraud.async=true}. In the default
 * synchronous mode payment-service calls this service over HTTP and waits, and no request event is
 * ever published for this listener to consume.
 *
 * <p>Safe to consume twice, which matters because delivery is at-least-once:
 * {@link FraudService#screen} is idempotent on payment id and returns the stored decision without
 * publishing anything a second time. A duplicate here therefore costs one wasted query rather than
 * a second, possibly different, verdict — and "possibly different" is the real hazard, since the
 * velocity window moves between deliveries.
 */
@Component
public class ScreeningRequestListener {

    private final FraudService fraudService;
    private final EventCodec eventCodec;

    public ScreeningRequestListener(FraudService fraudService, EventCodec eventCodec) {
        this.fraudService = fraudService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.FRAUD_CHECK_REQUESTED, groupId = "fraud-service")
    public void onScreeningRequested(String message) {
        EventEnvelope<FraudCheckRequested> event = eventCodec.decode(message, FraudCheckRequested.class);
        FraudCheckRequested requested = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            fraudService.screen(new ScreeningRequest(
                    requested.paymentId(),
                    requested.merchantId(),
                    requested.amount(),
                    requested.currency(),
                    requested.paymentMethodType()));
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
