package com.openpay.ledger.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.RefundSucceeded;
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
 * Reverses a capture when money goes back to the customer.
 *
 * <p>Posted as a new transaction rather than by amending the original: the journal is append-only,
 * and a correction that edits history is indistinguishable from a cover-up.
 *
 * <p>The payable goes negative when the payment was already settled, which is correct rather than
 * a bug. A negative liability is a receivable: the merchant has been paid for money they have
 * since given back, so they owe it to us, and settlement nets that against their next payout.
 *
 * <p>The platform fee is deliberately not returned. The work of processing the original payment
 * was still done, which is how most gateways price refunds.
 */
@Component
public class RefundSucceededListener {

    private static final Logger log = LoggerFactory.getLogger(RefundSucceededListener.class);
    private static final String REFERENCE_TYPE = "REFUND";

    private final LedgerService ledgerService;
    private final EventCodec eventCodec;

    public RefundSucceededListener(LedgerService ledgerService, EventCodec eventCodec) {
        this.ledgerService = ledgerService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.REFUND_SUCCEEDED, groupId = "ledger-service")
    public void onRefundSucceeded(String message) {
        EventEnvelope<RefundSucceeded> event = eventCodec.decode(message, RefundSucceeded.class);
        RefundSucceeded refund = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            ledgerService.post(new PostingRequest(
                    event.eventId(),
                    REFERENCE_TYPE,
                    refund.refundId(),
                    refund.currency(),
                    "Refund " + refund.refundId() + " of payment " + refund.paymentId(),
                    List.of(
                            new PostingRequest.Line(
                                    AccountCodes.MERCHANT_PAYABLE, refund.merchantId(),
                                    AccountType.LIABILITY, EntryDirection.DEBIT, refund.amount()),
                            new PostingRequest.Line(
                                    AccountCodes.GATEWAY_CLEARING, null, AccountType.ASSET,
                                    EntryDirection.CREDIT, refund.amount()))));

            log.info("Ledger reversed {} for refund {}", refund.amount(), refund.refundId());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
