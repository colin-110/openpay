package com.openpay.ledger.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.PaymentStatusUpdated;
import com.openpay.ledger.application.LedgerService;
import com.openpay.ledger.application.PostingRequest;
import com.openpay.ledger.domain.AccountCodes;
import com.openpay.ledger.domain.AccountType;
import com.openpay.ledger.domain.EntryDirection;
import com.openpay.observability.CorrelationIdFilter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Turns terminal payment outcomes into journal postings.
 *
 * <p>Only CAPTURED posts. AUTHORIZED reserves funds without moving them, and a failed payment
 * moved nothing at all — writing entries for either would inflate the books with money that does
 * not exist. Refunds and settlement will add their own postings when those phases land.
 */
@Component
public class PaymentStatusListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentStatusListener.class);
    private static final String CAPTURED = "CAPTURED";
    private static final String REFERENCE_TYPE = "PAYMENT";

    private final LedgerService ledgerService;
    private final EventCodec eventCodec;

    public PaymentStatusListener(LedgerService ledgerService, EventCodec eventCodec) {
        this.ledgerService = ledgerService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.PAYMENT_STATUS_UPDATED, groupId = "ledger-service")
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
            // Money arrived from the acquirer (asset up) and we now owe the merchant (liability up).
            ledgerService.post(new PostingRequest(
                    event.eventId(),
                    REFERENCE_TYPE,
                    payment.paymentId(),
                    payment.currency(),
                    "Capture of payment " + payment.paymentId(),
                    List.of(
                            new PostingRequest.Line(
                                    AccountCodes.GATEWAY_CLEARING, null, AccountType.ASSET,
                                    EntryDirection.DEBIT, payment.amount()),
                            new PostingRequest.Line(
                                    AccountCodes.MERCHANT_PAYABLE, payment.merchantId(), AccountType.LIABILITY,
                                    EntryDirection.CREDIT, payment.amount()))));
            log.info("Ledger recorded capture of payment {}", payment.paymentId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
