package com.openpay.settlement.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.RefundSucceeded;
import com.openpay.observability.CorrelationIdFilter;
import com.openpay.settlement.application.SettlementService;
import java.time.OffsetDateTime;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Accrues a successful refund as a negative payable so it nets against the next payout. */
@Component
public class RefundSucceededListener {

    private final SettlementService settlementService;
    private final EventCodec eventCodec;

    public RefundSucceededListener(SettlementService settlementService, EventCodec eventCodec) {
        this.settlementService = settlementService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.REFUND_SUCCEEDED, groupId = "settlement-service")
    public void onRefundSucceeded(String message) {
        EventEnvelope<RefundSucceeded> event = eventCodec.decode(message, RefundSucceeded.class);
        RefundSucceeded refund = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            settlementService.accrueRefund(
                    refund.merchantId(), refund.paymentId(), refund.refundId(), refund.currency(),
                    refund.amount(),
                    event.occurredAt() != null ? event.occurredAt() : OffsetDateTime.now());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
