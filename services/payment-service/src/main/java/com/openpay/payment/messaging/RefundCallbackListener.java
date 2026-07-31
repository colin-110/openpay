package com.openpay.payment.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.RefundCallbackReceived;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.payment.application.RefundService;
import com.openpay.payment.domain.RefundStatus;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Completes a refund from what the provider reported.
 *
 * <p>As with payments, a merchant cannot move a refund forward themselves; only a
 * signature-verified provider callback, or the router giving up, can finish one.
 */
@Component
public class RefundCallbackListener {

    private final RefundService refundService;
    private final EventCodec eventCodec;

    public RefundCallbackListener(RefundService refundService, EventCodec eventCodec) {
        this.refundService = refundService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.REFUND_CALLBACK_RECEIVED, groupId = "payment-service")
    public void onRefundCallback(String message) {
        EventEnvelope<RefundCallbackReceived> event =
                eventCodec.decode(message, RefundCallbackReceived.class);
        RefundCallbackReceived callback = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            RefundStatus target =
                    callback.outcome() == RefundCallbackReceived.RefundOutcome.SUCCEEDED
                            ? RefundStatus.SUCCEEDED
                            : RefundStatus.FAILED;
            refundService.applyOutcome(callback.refundId(), target, callback.failureReason());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
