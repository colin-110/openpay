package com.openpay.settlement;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.settlement.application.SettlementService;
import com.openpay.settlement.domain.Settlement;
import com.openpay.settlement.domain.SettlementItem;
import com.openpay.settlement.domain.SettlementItemRepository;
import com.openpay.settlement.domain.SettlementItemStatus;
import com.openpay.settlement.domain.SettlementItemType;
import com.openpay.settlement.domain.SettlementStatus;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "openpay.outbox.relay-enabled=false",
        "openpay.settlement.scheduled=false",
        "openpay.settlement.fee-basis-points=200",
        "openpay.settlement.fee-fixed-minor=0"
})
@Testcontainers
class SettlementIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private SettlementService settlementService;

    @Autowired
    private SettlementItemRepository itemRepository;

    @Test
    void accruesACapturedPaymentWithItsFee() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        SettlementItem item = settlementService.accrue(
                merchantId, paymentId, "USD", 10_000, OffsetDateTime.now());

        assertThat(item.getGrossAmount()).isEqualTo(10_000L);
        assertThat(item.getFeeAmount()).isEqualTo(200L);
        assertThat(item.getNetAmount()).isEqualTo(9_800L);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.PENDING);
    }

    @Test
    void aRedeliveredCaptureAccruesOnlyOnce() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        SettlementItem first = settlementService.accrue(
                merchantId, paymentId, "USD", 5_000, OffsetDateTime.now());
        SettlementItem second = settlementService.accrue(
                merchantId, paymentId, "USD", 5_000, OffsetDateTime.now());

        // Paying a merchant twice for one payment is the failure this guards against.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(itemRepository.findAll().stream()
                .filter(i -> i.getPaymentId().equals(paymentId))
                .count()).isEqualTo(1);
    }

    @Test
    void batchesManyPaymentsIntoOnePayoutPerMerchant() {
        UUID merchantId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 20_000, OffsetDateTime.now());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 30_000, OffsetDateTime.now());

        List<Settlement> created = settlementService.runSettlement(date);
        Settlement mine = forMerchant(created, merchantId, "USD");

        assertThat(mine.getItemCount()).isEqualTo(3);
        assertThat(mine.getGrossAmount()).isEqualTo(60_000L);
        assertThat(mine.getFeeAmount()).isEqualTo(1_200L);
        assertThat(mine.getNetAmount()).isEqualTo(58_800L);
        assertThat(mine.getStatus()).isEqualTo(SettlementStatus.CREATED);
    }

    @Test
    void aSettlementTotalsExactlyTheItemsInsideIt() {
        UUID merchantId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        for (long amount : new long[] {1_234, 5_678, 999, 250_000}) {
            settlementService.accrue(merchantId, UUID.randomUUID(), "USD", amount, OffsetDateTime.now());
        }

        Settlement settlement = forMerchant(settlementService.runSettlement(date), merchantId, "USD");
        List<SettlementItem> items = settlementService.itemsFor(settlement.getId());

        // A payout has to reconcile against the payments behind it, or it cannot be audited.
        assertThat(items.stream().mapToLong(SettlementItem::getGrossAmount).sum())
                .isEqualTo(settlement.getGrossAmount());
        assertThat(items.stream().mapToLong(SettlementItem::getFeeAmount).sum())
                .isEqualTo(settlement.getFeeAmount());
        assertThat(items.stream().mapToLong(SettlementItem::getNetAmount).sum())
                .isEqualTo(settlement.getNetAmount());
        assertThat(settlement.getFeeAmount() + settlement.getNetAmount())
                .isEqualTo(settlement.getGrossAmount());
    }

    @Test
    void currenciesSettleSeparately() {
        UUID merchantId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        settlementService.accrue(merchantId, UUID.randomUUID(), "EUR", 40_000, OffsetDateTime.now());

        List<Settlement> created = settlementService.runSettlement(date);

        assertThat(forMerchant(created, merchantId, "USD").getGrossAmount()).isEqualTo(10_000L);
        assertThat(forMerchant(created, merchantId, "EUR").getGrossAmount()).isEqualTo(40_000L);
    }

    @Test
    void merchantsAreNeverBatchedTogether() {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        settlementService.accrue(merchantA, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        settlementService.accrue(merchantB, UUID.randomUUID(), "USD", 70_000, OffsetDateTime.now());

        List<Settlement> created = settlementService.runSettlement(date);

        assertThat(forMerchant(created, merchantA, "USD").getGrossAmount()).isEqualTo(10_000L);
        assertThat(forMerchant(created, merchantB, "USD").getGrossAmount()).isEqualTo(70_000L);
    }

    @Test
    void aSecondRunDoesNotPayTheSameMoneyAgain() {
        UUID merchantId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());

        Settlement first = forMerchant(settlementService.runSettlement(date), merchantId, "USD");
        List<Settlement> secondRun = settlementService.runSettlement(date);

        // Items are already SETTLED, so a re-run finds nothing of this merchant's to batch.
        assertThat(secondRun.stream().anyMatch(s -> s.getMerchantId().equals(merchantId))).isFalse();
        assertThat(settlementService.get(first.getId()).getItemCount()).isEqualTo(1);
    }

    @Test
    void alreadySettledItemsAreNeverPickedUpAgain() {
        UUID merchantId = UUID.randomUUID();
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        settlementService.runSettlement(LocalDate.now().plusDays(uniqueDayOffset()));

        // A later window with new money must contain only the new money.
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 500, OffsetDateTime.now());
        Settlement later = forMerchant(
                settlementService.runSettlement(LocalDate.now().plusDays(uniqueDayOffset())),
                merchantId, "USD");

        assertThat(later.getItemCount()).isEqualTo(1);
        assertThat(later.getGrossAmount()).isEqualTo(500L);
    }

    @Test
    void anEmptyWindowCreatesNothing() {
        // Running with nothing eligible must not produce an empty payout record.
        List<Settlement> created = settlementService.runSettlement(LocalDate.now().plusDays(uniqueDayOffset()));
        assertThat(created).allSatisfy(settlement -> assertThat(settlement.getItemCount()).isPositive());
    }

    @Test
    void aSettlementCanBeCompletedOnce() {
        UUID merchantId = UUID.randomUUID();
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        Settlement settlement = forMerchant(
                settlementService.runSettlement(LocalDate.now().plusDays(uniqueDayOffset())),
                merchantId, "USD");

        assertThat(settlementService.complete(settlement.getId()).getStatus())
                .isEqualTo(SettlementStatus.COMPLETED);
    }


    @Test
    void aRefundNetsAgainstCapturesInTheSameWindow() {
        UUID merchantId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 50_000, OffsetDateTime.now());
        settlementService.accrueRefund(
                merchantId, UUID.randomUUID(), UUID.randomUUID(), "USD", 20_000, OffsetDateTime.now());

        Settlement settlement = forMerchant(settlementService.runSettlement(date), merchantId, "USD");

        // 50000 in, 20000 back out, 1000 fee on the capture only.
        assertThat(settlement.getGrossAmount()).isEqualTo(30_000L);
        assertThat(settlement.getFeeAmount()).isEqualTo(1_000L);
        assertThat(settlement.getNetAmount()).isEqualTo(29_000L);
        assertThat(settlement.getItemCount()).isEqualTo(2);
    }

    @Test
    void aWindowThatNetsNegativeCarriesForwardInsteadOfPayingOut() {
        UUID merchantId = UUID.randomUUID();
        LocalDate date = LocalDate.now().plusDays(uniqueDayOffset());
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        settlementService.accrueRefund(
                merchantId, UUID.randomUUID(), UUID.randomUUID(), "USD", 40_000, OffsetDateTime.now());

        List<Settlement> created = settlementService.runSettlement(date);

        // Paying out a negative amount is meaningless, and zeroing it would write off money the
        // merchant owes us.
        assertThat(created.stream().anyMatch(s -> s.getMerchantId().equals(merchantId))).isFalse();
    }

    @Test
    void aCarriedForwardDeficitReducesTheNextPayout() {
        UUID merchantId = UUID.randomUUID();
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());
        settlementService.accrueRefund(
                merchantId, UUID.randomUUID(), UUID.randomUUID(), "USD", 40_000, OffsetDateTime.now());
        settlementService.runSettlement(LocalDate.now().plusDays(uniqueDayOffset()));

        // New volume arrives in a later window; the earlier deficit is still owed.
        settlementService.accrue(merchantId, UUID.randomUUID(), "USD", 100_000, OffsetDateTime.now());
        Settlement later = forMerchant(
                settlementService.runSettlement(LocalDate.now().plusDays(uniqueDayOffset())),
                merchantId, "USD");

        // 10000 + 100000 captured, 40000 refunded => 70000 gross across all three items.
        assertThat(later.getItemCount()).isEqualTo(3);
        assertThat(later.getGrossAmount()).isEqualTo(70_000L);
        assertThat(later.getNetAmount()).isEqualTo(70_000L - 2_200L);
    }

    @Test
    void aRedeliveredRefundAccruesOnlyOnce() {
        UUID merchantId = UUID.randomUUID();
        UUID refundId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        SettlementItem first = settlementService.accrueRefund(
                merchantId, paymentId, refundId, "USD", 5_000, OffsetDateTime.now());
        SettlementItem second = settlementService.accrueRefund(
                merchantId, paymentId, refundId, "USD", 5_000, OffsetDateTime.now());

        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void aPaymentCanBeBothCapturedAndRefunded() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        settlementService.accrue(merchantId, paymentId, "USD", 10_000, OffsetDateTime.now());
        SettlementItem refund = settlementService.accrueRefund(
                merchantId, paymentId, UUID.randomUUID(), "USD", 10_000, OffsetDateTime.now());

        // The old unique constraint was on payment_id alone, which would have made this impossible.
        assertThat(refund.getGrossAmount()).isEqualTo(-10_000L);
        assertThat(refund.getItemType()).isEqualTo(SettlementItemType.REFUND);
    }

    @Test
    void refundsCarryNoFee() {
        UUID merchantId = UUID.randomUUID();
        SettlementItem refund = settlementService.accrueRefund(
                merchantId, UUID.randomUUID(), UUID.randomUUID(), "USD", 7_000, OffsetDateTime.now());

        // The original payment was still processed, so the platform keeps what it charged for it.
        assertThat(refund.getFeeAmount()).isZero();
        assertThat(refund.getNetAmount()).isEqualTo(-7_000L);
    }

    private Settlement forMerchant(List<Settlement> settlements, UUID merchantId, String currency) {
        return settlements.stream()
                .filter(s -> s.getMerchantId().equals(merchantId) && s.getCurrency().equals(currency))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no " + currency + " settlement created for merchant " + merchantId));
    }

    /**
     * Each test needs its own settlement date: one settlement per merchant, currency, and date is
     * a real constraint, and tests share a database.
     */
    private static int dayCounter = 0;

    private static synchronized int uniqueDayOffset() {
        return ++dayCounter;
    }
}
