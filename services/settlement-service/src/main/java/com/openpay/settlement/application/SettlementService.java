package com.openpay.settlement.application;

import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.SettlementCreated;
import com.openpay.outbox.OutboxWriter;
import com.openpay.settlement.domain.Settlement;
import com.openpay.settlement.domain.SettlementItem;
import com.openpay.settlement.domain.SettlementItemRepository;
import com.openpay.settlement.domain.SettlementItemType;
import com.openpay.settlement.domain.SettlementRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Accrues captured payments and batches them into payouts.
 *
 * <p>Accrual and batching are separate on purpose. A payment becomes payable the moment it is
 * captured, but money leaves on a schedule, and keeping the two apart is what makes a payout
 * auditable back to the exact payments inside it.
 */
@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final String AGGREGATE_TYPE = "settlement";

    private final SettlementItemRepository itemRepository;
    private final SettlementRepository settlementRepository;
    private final FeeCalculator feeCalculator;
    private final SettlementProperties properties;
    private final OutboxWriter outboxWriter;

    public SettlementService(
            SettlementItemRepository itemRepository,
            SettlementRepository settlementRepository,
            FeeCalculator feeCalculator,
            SettlementProperties properties,
            OutboxWriter outboxWriter) {
        this.itemRepository = itemRepository;
        this.settlementRepository = settlementRepository;
        this.feeCalculator = feeCalculator;
        this.properties = properties;
        this.outboxWriter = outboxWriter;
    }

    /**
     * Records a captured payment as payable.
     *
     * <p>Idempotent through the unique constraint on payment_id rather than a preceding lookup: a
     * redelivered capture must never accrue the same money twice, and a lookup loses that race.
     */
    @Transactional
    public SettlementItem accrue(
            UUID merchantId, UUID paymentId, String currency, long grossAmount, OffsetDateTime capturedAt) {

        Optional<SettlementItem> existing =
                itemRepository.findByPaymentIdAndItemType(paymentId, SettlementItemType.CAPTURE);
        if (existing.isPresent()) {
            log.info("Payment {} is already accrued, ignoring redelivery", paymentId);
            return existing.get();
        }

        FeeCalculator.Fee fee = feeCalculator.calculate(grossAmount);
        if (fee.net() < 0) {
            // Visible rather than silently clamped: a payment too small to cover its own fee is a
            // pricing decision, not something this service should quietly absorb.
            log.warn("Payment {} nets negative after fees (gross {}, fee {})",
                    paymentId, fee.gross(), fee.fee());
        }

        try {
            return itemRepository.saveAndFlush(new SettlementItem(
                    merchantId, paymentId, currency, fee.gross(), fee.fee(), fee.net(), capturedAt));
        } catch (DataIntegrityViolationException exception) {
            log.info("Concurrent accrual for payment {}, keeping the winner", paymentId);
            return itemRepository.findByPaymentIdAndItemType(paymentId, SettlementItemType.CAPTURE)
                    .orElseThrow(() -> exception);
        }
    }

    /**
     * Records a refund as a negative payable.
     *
     * <p>Carry-forward falls out of this rather than needing its own mechanism: the refund sits in
     * the same pending pool as captures and nets against them when the window closes. If a merchant
     * refunds more than they took in a period, the group's net goes negative and no payout is made,
     * so the deficit stays pending and reduces the next payout instead.
     *
     * <p>The fee is not returned. The original payment was still processed, which is how most
     * gateways price a refund, so only the gross comes back off the payable.
     */
    @Transactional
    public SettlementItem accrueRefund(
            UUID merchantId, UUID paymentId, UUID refundId, String currency,
            long refundAmount, OffsetDateTime refundedAt) {

        Optional<SettlementItem> existing = itemRepository.findByRefundId(refundId);
        if (existing.isPresent()) {
            log.info("Refund {} is already accrued, ignoring redelivery", refundId);
            return existing.get();
        }

        try {
            return itemRepository.saveAndFlush(new SettlementItem(
                    merchantId, paymentId, refundId, SettlementItemType.REFUND, currency,
                    -refundAmount, 0L, -refundAmount, refundedAt));
        } catch (DataIntegrityViolationException exception) {
            log.info("Concurrent accrual for refund {}, keeping the winner", refundId);
            return itemRepository.findByRefundId(refundId).orElseThrow(() -> exception);
        }
    }

    /**
     * Batches every eligible item into one settlement per merchant, currency, and date.
     *
     * @return the settlements created by this run, empty if nothing was eligible
     */
    @Transactional
    public List<Settlement> runSettlement(LocalDate settlementDate) {
        OffsetDateTime eligibleBefore = OffsetDateTime.now().minus(properties.getHoldPeriod());
        List<SettlementItem> eligible = itemRepository.claimEligible(eligibleBefore);

        if (eligible.isEmpty()) {
            log.info("Settlement run for {} found nothing eligible", settlementDate);
            return List.of();
        }

        // Group before writing anything: one payout per merchant per currency, not one per payment.
        Map<GroupKey, List<SettlementItem>> grouped = new LinkedHashMap<>();
        for (SettlementItem item : eligible) {
            grouped.computeIfAbsent(
                    new GroupKey(item.getMerchantId(), item.getCurrency()), key -> new ArrayList<>())
                    .add(item);
        }

        List<Settlement> created = new ArrayList<>();
        for (Map.Entry<GroupKey, List<SettlementItem>> entry : grouped.entrySet()) {
            settle(entry.getKey(), entry.getValue(), settlementDate).ifPresent(created::add);
        }

        log.info("Settlement run for {} created {} settlements from {} items",
                settlementDate, created.size(), eligible.size());
        return created;
    }

    @Transactional
    public Settlement complete(UUID settlementId) {
        Settlement settlement = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(settlementId));
        settlement.markCompleted();
        return settlementRepository.save(settlement);
    }

    @Transactional(readOnly = true)
    public Settlement get(UUID settlementId) {
        return settlementRepository.findById(settlementId)
                .orElseThrow(() -> new SettlementNotFoundException(settlementId));
    }

    @Transactional(readOnly = true)
    public List<SettlementItem> itemsFor(UUID settlementId) {
        return itemRepository.findBySettlementId(settlementId);
    }

    private Optional<Settlement> settle(GroupKey key, List<SettlementItem> items, LocalDate settlementDate) {
        // A merchant already settled for this currency and date must not be settled again; the run
        // is expected to be re-runnable without producing a second payout for the same money.
        if (settlementRepository.findByMerchantIdAndCurrencyAndSettlementDate(
                key.merchantId(), key.currency(), settlementDate).isPresent()) {
            log.info("Merchant {} already has a {} settlement for {}, skipping",
                    key.merchantId(), key.currency(), settlementDate);
            return Optional.empty();
        }

        long gross = items.stream().mapToLong(SettlementItem::getGrossAmount).sum();
        long fees = items.stream().mapToLong(SettlementItem::getFeeAmount).sum();
        long net = items.stream().mapToLong(SettlementItem::getNetAmount).sum();

        // Refunds can outweigh captures in a window. Paying out a negative amount is meaningless,
        // and zeroing it would quietly write off money the merchant owes us, so the items stay
        // pending and carry into the next window instead.
        if (net <= 0) {
            log.info("Merchant {} nets {} in {} for {}: carrying {} items forward rather than paying out",
                    key.merchantId(), net, key.currency(), settlementDate, items.size());
            return Optional.empty();
        }

        Settlement settlement = settlementRepository.saveAndFlush(new Settlement(
                key.merchantId(), key.currency(), settlementDate, gross, fees, net, items.size()));

        items.forEach(item -> item.assignTo(settlement.getId()));
        itemRepository.saveAll(items);

        // Written in the same transaction as the settlement, so the ledger can never be told about
        // a payout that rolled back, and a payout can never commit without telling the ledger.
        outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.SETTLEMENT_CREATED, settlement.getId(),
                new SettlementCreated(
                        settlement.getId(), key.merchantId(), key.currency(), settlementDate,
                        gross, fees, net, items.size()));

        log.info("Settled {} items for merchant {} in {}: gross {}, fees {}, net {}",
                items.size(), key.merchantId(), key.currency(), gross, fees, net);
        return Optional.of(settlement);
    }

    private record GroupKey(UUID merchantId, String currency) {
    }
}
