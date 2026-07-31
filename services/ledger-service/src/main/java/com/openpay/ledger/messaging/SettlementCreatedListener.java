package com.openpay.ledger.messaging;

import com.openpay.events.EventCodec;
import com.openpay.events.EventEnvelope;
import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.SettlementCreated;
import com.openpay.ledger.application.LedgerService;
import com.openpay.ledger.application.PostingRequest;
import com.openpay.ledger.domain.AccountCodes;
import com.openpay.ledger.domain.AccountType;
import com.openpay.ledger.domain.EntryDirection;
import com.openpay.observability.CorrelationIdFilter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Clears a merchant's payable when their payout is batched.
 *
 * <p>Without this the ledger only ever grows: capture credits the payable and nothing debits it,
 * so the books would report money owed to a merchant who has already been paid.
 *
 * <p>The posting splits three ways. The full gross is debited because that is what we owed. The
 * fee is credited to revenue because the platform keeps it. The net is credited to clearing
 * because that is the cash actually leaving. Gross equals fee plus net, so it balances.
 */
@Component
public class SettlementCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementCreatedListener.class);
    private static final String REFERENCE_TYPE = "SETTLEMENT";

    private final LedgerService ledgerService;
    private final EventCodec eventCodec;

    public SettlementCreatedListener(LedgerService ledgerService, EventCodec eventCodec) {
        this.ledgerService = ledgerService;
        this.eventCodec = eventCodec;
    }

    @KafkaListener(topics = OpenPayTopics.SETTLEMENT_CREATED, groupId = "ledger-service")
    public void onSettlementCreated(String message) {
        EventEnvelope<SettlementCreated> event = eventCodec.decode(message, SettlementCreated.class);
        SettlementCreated settlement = event.payload();

        if (event.correlationId() != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, event.correlationId());
        }
        try {
            List<PostingRequest.Line> lines = new ArrayList<>();
            lines.add(new PostingRequest.Line(
                    AccountCodes.MERCHANT_PAYABLE, settlement.merchantId(), AccountType.LIABILITY,
                    EntryDirection.DEBIT, settlement.grossAmount()));

            // A zero fee would be a zero-amount entry, which the journal rejects; omitting the line
            // keeps the transaction balanced without writing a meaningless row.
            if (settlement.feeAmount() > 0) {
                lines.add(new PostingRequest.Line(
                        AccountCodes.PLATFORM_REVENUE, null, AccountType.REVENUE,
                        EntryDirection.CREDIT, settlement.feeAmount()));
            }
            if (settlement.netAmount() > 0) {
                lines.add(new PostingRequest.Line(
                        AccountCodes.GATEWAY_CLEARING, null, AccountType.ASSET,
                        EntryDirection.CREDIT, settlement.netAmount()));
            }

            ledgerService.post(new PostingRequest(
                    event.eventId(),
                    REFERENCE_TYPE,
                    settlement.settlementId(),
                    settlement.currency(),
                    "Settlement " + settlement.settlementId() + " covering "
                            + settlement.itemCount() + " payments",
                    lines));

            log.info("Ledger cleared payable for settlement {} (gross {}, fee {}, net {})",
                    settlement.settlementId(), settlement.grossAmount(),
                    settlement.feeAmount(), settlement.netAmount());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
