package com.openpay.router.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.RefundCreated;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.router.application.RefundRoutingService;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class RefundCreatedListener {

    private final RefundRoutingService refundRoutingService;
    private final EventCodec eventCodec;

    public RefundCreatedListener(RefundRoutingService refundRoutingService, EventCodec eventCodec) {
        this.refundRoutingService = refundRoutingService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.REFUND_CREATED, groupId = "provider-router-service")
    public void onRefundCreated(String message) {
        EventEnvelope<RefundCreated> event = eventCodec.decode(message, RefundCreated.class);
        RefundCreated refund = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            refundRoutingService.routeRefund(
                    refund.refundId(), refund.paymentId(), refund.amount(),
                    refund.currency(), event.correlationId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
