package com.openpay.settlement.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.PaymentStatusUpdated;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.settlement.application.SettlementService;
import java.time.OffsetDateTime;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Accrues a payable the moment a payment is captured.
 *
 * <p>Only CAPTURED accrues. An authorised payment has reserved funds that may never be taken, and
 * a failed one moved nothing; paying out against either would send money we do not have.
 */
@Component
public class PaymentCapturedListener {

    private static final String CAPTURED = "CAPTURED";

    private final SettlementService settlementService;
    private final EventCodec eventCodec;

    public PaymentCapturedListener(SettlementService settlementService, EventCodec eventCodec) {
        this.settlementService = settlementService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.PAYMENT_STATUS_UPDATED, groupId = "settlement-service")
    public void onPaymentStatusUpdated(String message) {
        EventEnvelope<PaymentStatusUpdated> event =
                eventCodec.decode(message, PaymentStatusUpdated.class);
        PaymentStatusUpdated payment = event.payload();

        if (!CAPTURED.equals(payment.toStatus())) {
            return;
        }

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            settlementService.accrue(
                    payment.merchantId(),
                    payment.paymentId(),
                    payment.currency(),
                    payment.amount(),
                    event.occurredAt() != null ? event.occurredAt() : OffsetDateTime.now());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
