package com.openpay.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.ledger.application.AccountBalance;
import com.openpay.ledger.application.LedgerService;
import com.openpay.ledger.application.PostingRequest;
import com.openpay.ledger.application.UnbalancedTransactionException;
import com.openpay.ledger.domain.AccountCodes;
import com.openpay.ledger.domain.AccountType;
import com.openpay.ledger.domain.EntryDirection;
import com.openpay.ledger.domain.LedgerEntry;
import com.openpay.ledger.domain.LedgerEntryRepository;
import com.openpay.ledger.domain.LedgerTransaction;
import com.openpay.ledger.domain.LedgerTransactionRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
class LedgerPostingIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private LedgerTransactionRepository transactionRepository;

    @Autowired
    private LedgerEntryRepository entryRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void postsBothSidesOfACapture() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        LedgerTransaction transaction = ledgerService.post(capture(UUID.randomUUID(), paymentId, merchantId, 25_000));

        List<LedgerEntry> entries = entryRepository.findByTransactionId(transaction.getId());
        assertThat(entries).hasSize(2);
        assertThat(entries).extracting(LedgerEntry::getDirection)
                .containsExactlyInAnyOrder(EntryDirection.DEBIT, EntryDirection.CREDIT);
        assertThat(entries).allSatisfy(entry -> assertThat(entry.getAmount()).isEqualTo(25_000L));
    }

    @Test
    void debitsEqualCreditsAcrossEveryTransaction() {
        UUID merchantId = UUID.randomUUID();
        for (int i = 0; i < 5; i++) {
            ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 1_000L * (i + 1)));
        }

        // The invariant that makes a ledger a ledger, checked over the whole journal.
        long debits = 0;
        long credits = 0;
        for (LedgerEntry entry : entryRepository.findAll()) {
            if (entry.getDirection() == EntryDirection.DEBIT) {
                debits += entry.getAmount();
            } else {
                credits += entry.getAmount();
            }
        }
        assertThat(debits).isEqualTo(credits);
    }

    @Test
    void refusesAnUnbalancedTransaction() {
        UUID merchantId = UUID.randomUUID();
        PostingRequest unbalanced = new PostingRequest(
                UUID.randomUUID(), "PAYMENT", UUID.randomUUID(), "USD", "wrong",
                List.of(
                        new PostingRequest.Line(AccountCodes.GATEWAY_CLEARING, null, AccountType.ASSET,
                                EntryDirection.DEBIT, 10_000),
                        new PostingRequest.Line(AccountCodes.MERCHANT_PAYABLE, merchantId, AccountType.LIABILITY,
                                EntryDirection.CREDIT, 9_000)));

        long transactionsBefore = transactionRepository.count();
        long entriesBefore = entryRepository.count();

        assertThatThrownBy(() -> ledgerService.post(unbalanced))
                .isInstanceOf(UnbalancedTransactionException.class);

        // Nothing may be written when the posting is rejected, or the journal is already wrong.
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore);
        assertThat(entryRepository.count()).isEqualTo(entriesBefore);
    }

    @Test
    void refusesASingleSidedTransaction() {
        assertThatThrownBy(() -> ledgerService.post(new PostingRequest(
                UUID.randomUUID(), "PAYMENT", UUID.randomUUID(), "USD", "one sided",
                List.of(new PostingRequest.Line(AccountCodes.GATEWAY_CLEARING, null, AccountType.ASSET,
                        EntryDirection.DEBIT, 10_000)))))
                .isInstanceOf(UnbalancedTransactionException.class);
    }

    @Test
    void redeliveryOfTheSameEventPostsOnlyOnce() {
        UUID eventId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        long transactionsBefore = transactionRepository.count();
        long entriesBefore = entryRepository.count();

        LedgerTransaction first = ledgerService.post(capture(eventId, paymentId, merchantId, 5_000));
        LedgerTransaction second = ledgerService.post(capture(eventId, paymentId, merchantId, 5_000));

        // At-least-once delivery must not become at-least-once accounting.
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(transactionRepository.count()).isEqualTo(transactionsBefore + 1);
        assertThat(entryRepository.count()).isEqualTo(entriesBefore + 2);
    }

    @Test
    void balancesAreNormalisedByAccountType() {
        UUID merchantId = UUID.randomUUID();
        // Gateway clearing is platform-wide and shared with every other test in this class, so it
        // is measured as a delta. The merchant payable is per-merchant and therefore isolated.
        long clearingBefore = clearingBalance();

        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 30_000));
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 20_000));

        AccountBalance payable = ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantId, "USD");

        // A liability grows on credits, an asset on debits; both should read positive.
        assertThat(payable.balance()).isEqualTo(50_000L);
        assertThat(payable.totalCredits()).isEqualTo(50_000L);
        assertThat(clearingBalance() - clearingBefore).isEqualTo(50_000L);
    }

    private long clearingBalance() {
        try {
            return ledgerService.balance(AccountCodes.GATEWAY_CLEARING, null, "USD").balance();
        } catch (RuntimeException notCreatedYet) {
            return 0L;
        }
    }

    @Test
    void oneMerchantsPayableIsNotAnothers() {
        UUID merchantA = UUID.randomUUID();
        UUID merchantB = UUID.randomUUID();
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantA, 7_000));

        assertThat(ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantA, "USD").balance())
                .isEqualTo(7_000L);
        assertThatThrownBy(() -> ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantB, "USD"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void currenciesAreNeverMixedInOneAccount() {
        UUID merchantId = UUID.randomUUID();
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 10_000, "USD"));
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 40_000, "EUR"));

        assertThat(ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantId, "USD").balance())
                .isEqualTo(10_000L);
        assertThat(ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantId, "EUR").balance())
                .isEqualTo(40_000L);
    }

    @Test
    @Transactional
    void theJournalCannotBeRewritten() {
        UUID merchantId = UUID.randomUUID();
        LedgerTransaction transaction =
                ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 1_234));
        UUID entryId = entryRepository.findByTransactionId(transaction.getId()).get(0).getId();

        // Enforced by a database trigger, so it holds against any client, not just this service.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                    "UPDATE ledger_entries SET amount = 1 WHERE id = :id")
                    .setParameter("id", entryId)
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    @Transactional
    void journalEntriesCannotBeDeleted() {
        UUID merchantId = UUID.randomUUID();
        LedgerTransaction transaction =
                ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 999));
        UUID entryId = entryRepository.findByTransactionId(transaction.getId()).get(0).getId();

        assertThatThrownBy(() -> {
            entityManager.createNativeQuery("DELETE FROM ledger_entries WHERE id = :id")
                    .setParameter("id", entryId)
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }


    @Test
    void settlingClearsTheMerchantPayableToZero() {
        UUID merchantId = UUID.randomUUID();
        // Capture 30000, then settle it: 600 fee (2%) kept, 29400 paid out.
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 30_000));
        assertThat(ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantId, "USD").balance())
                .isEqualTo(30_000L);

        ledgerService.post(settlement(UUID.randomUUID(), merchantId, 30_000, 600, 29_400));

        // The whole point: a merchant who has been paid is no longer owed anything.
        assertThat(ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantId, "USD").balance())
                .isZero();
    }

    @Test
    void feesLandInRevenueAndCashLeavesClearing() {
        UUID merchantId = UUID.randomUUID();
        long clearingBefore = clearingBalance();
        long revenueBefore = revenueBalance();

        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 30_000));
        ledgerService.post(settlement(UUID.randomUUID(), merchantId, 30_000, 600, 29_400));

        // Clearing took in 30000 and paid out 29400, keeping the 600 that became revenue.
        assertThat(clearingBalance() - clearingBefore).isEqualTo(600L);
        assertThat(revenueBalance() - revenueBefore).isEqualTo(600L);
    }

    @Test
    void aSettlementPostingBalances() {
        UUID merchantId = UUID.randomUUID();
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 12_345));

        LedgerTransaction transaction =
                ledgerService.post(settlement(UUID.randomUUID(), merchantId, 12_345, 247, 12_098));

        long debits = 0;
        long credits = 0;
        for (LedgerEntry entry : entryRepository.findByTransactionId(transaction.getId())) {
            if (entry.getDirection() == EntryDirection.DEBIT) {
                debits += entry.getAmount();
            } else {
                credits += entry.getAmount();
            }
        }
        assertThat(debits).isEqualTo(credits).isEqualTo(12_345L);
    }

    @Test
    void aRedeliveredSettlementDoesNotClearTwice() {
        UUID merchantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        ledgerService.post(capture(UUID.randomUUID(), UUID.randomUUID(), merchantId, 10_000));

        ledgerService.post(settlement(eventId, merchantId, 10_000, 200, 9_800));
        ledgerService.post(settlement(eventId, merchantId, 10_000, 200, 9_800));

        // Double-clearing would drive the payable negative, making it look like we overpaid.
        assertThat(ledgerService.balance(AccountCodes.MERCHANT_PAYABLE, merchantId, "USD").balance())
                .isZero();
    }

    private long revenueBalance() {
        try {
            return ledgerService.balance(AccountCodes.PLATFORM_REVENUE, null, "USD").balance();
        } catch (RuntimeException notCreatedYet) {
            return 0L;
        }
    }

    private PostingRequest settlement(UUID eventId, UUID merchantId, long gross, long fee, long net) {
        return new PostingRequest(
                eventId, "SETTLEMENT", UUID.randomUUID(), "USD", "Settlement payout",
                List.of(
                        new PostingRequest.Line(AccountCodes.MERCHANT_PAYABLE, merchantId,
                                AccountType.LIABILITY, EntryDirection.DEBIT, gross),
                        new PostingRequest.Line(AccountCodes.PLATFORM_REVENUE, null,
                                AccountType.REVENUE, EntryDirection.CREDIT, fee),
                        new PostingRequest.Line(AccountCodes.GATEWAY_CLEARING, null,
                                AccountType.ASSET, EntryDirection.CREDIT, net)));
    }

    private PostingRequest capture(UUID eventId, UUID paymentId, UUID merchantId, long amount) {
        return capture(eventId, paymentId, merchantId, amount, "USD");
    }

    private PostingRequest capture(UUID eventId, UUID paymentId, UUID merchantId, long amount, String currency) {
        return new PostingRequest(
                eventId, "PAYMENT", paymentId, currency, "Capture of payment " + paymentId,
                List.of(
                        new PostingRequest.Line(AccountCodes.GATEWAY_CLEARING, null, AccountType.ASSET,
                                EntryDirection.DEBIT, amount),
                        new PostingRequest.Line(AccountCodes.MERCHANT_PAYABLE, merchantId, AccountType.LIABILITY,
                                EntryDirection.CREDIT, amount)));
    }
}
