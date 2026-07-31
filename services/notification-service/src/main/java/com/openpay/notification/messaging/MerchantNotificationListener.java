package com.openpay.notification.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.PaymentStatusUpdated;
import com.openpay.events.payload.RefundSucceeded;
import com.openpay.notification.application.DeliveryQueue;
import com.openpay.observability.CorrelationIdFilter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Decides what a merchant is actually told about.
 *
 * <p>Not every internal state change deserves a webhook. PENDING_PROVIDER means we are mid
 * conversation with an acquirer, which is our concern rather than the merchant's, and sending it
 * would train them to ignore our notifications. Only outcomes they can act on are forwarded.
 */
@Component
public class MerchantNotificationListener {

    private static final Set<String> NOTIFIABLE_STATUSES =
            Set.of("CAPTURED", "FAILED", "CANCELLED", "REFUNDED");

    private final DeliveryQueue deliveryQueue;
    private final EventCodec eventCodec;

    public MerchantNotificationListener(DeliveryQueue deliveryQueue, EventCodec eventCodec) {
        this.deliveryQueue = deliveryQueue;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.PAYMENT_STATUS_UPDATED, groupId = "notification-service")
    public void onPaymentStatusUpdated(String message) {
        EventEnvelope<PaymentStatusUpdated> event =
                eventCodec.decode(message, PaymentStatusUpdated.class);
        PaymentStatusUpdated payment = event.payload();

        if (!NOTIFIABLE_STATUSES.contains(payment.toStatus())) {
            return;
        }

        String eventType = "payment." + payment.toStatus().toLowerCase();
        withCorrelation(event.correlationId(), () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", eventType);
            body.put("paymentId", payment.paymentId());
            body.put("status", payment.toStatus());
            body.put("amount", payment.amount());
            body.put("currency", payment.currency());
            body.put("occurredAt", event.occurredAt());

            deliveryQueue.enqueue(payment.merchantId(), event.eventId(), eventType, body);
        });
    }

    @KafkaListener(topics = OpenPayTopics.REFUND_SUCCEEDED, groupId = "notification-service")
    public void onRefundSucceeded(String message) {
        EventEnvelope<RefundSucceeded> event = eventCodec.decode(message, RefundSucceeded.class);
        RefundSucceeded refund = event.payload();

        withCorrelation(event.correlationId(), () -> {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", "refund.succeeded");
            body.put("refundId", refund.refundId());
            body.put("paymentId", refund.paymentId());
            body.put("amount", refund.amount());
            body.put("currency", refund.currency());
            body.put("occurredAt", event.occurredAt());

            deliveryQueue.enqueue(refund.merchantId(), event.eventId(), "refund.succeeded", body);
        });
    }

    private void withCorrelation(String correlationId, Runnable action) {
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            action.run();
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
