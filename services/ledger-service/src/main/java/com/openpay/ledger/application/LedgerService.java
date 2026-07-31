package com.openpay.ledger.application;

import com.openpay.ledger.domain.AccountType;
import com.openpay.ledger.domain.EntryDirection;
import com.openpay.ledger.domain.LedgerAccount;
import com.openpay.ledger.domain.LedgerAccountRepository;
import com.openpay.ledger.domain.LedgerEntry;
import com.openpay.ledger.domain.LedgerEntryRepository;
import com.openpay.ledger.domain.LedgerTransaction;
import com.openpay.ledger.domain.LedgerTransactionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts balanced transactions to the journal.
 *
 * <p>Two rules are absolute here. Debits must equal credits, checked before anything is written,
 * because an unbalanced journal cannot be repaired by a later correction — every report drawn from
 * it is wrong from that moment. And one source event posts at most once, enforced by a unique
 * constraint rather than by a lookup, because a lookup loses to a concurrent redelivery.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerAccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;

    public LedgerService(
            LedgerAccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
    }

    /**
     * @return the transaction that now represents this event, whether it was posted here or by an
     *     earlier delivery of the same event
     */
    @Transactional
    public LedgerTransaction post(PostingRequest request) {
        validate(request);

        Optional<LedgerTransaction> alreadyPosted = transactionRepository.findByEventId(request.eventId());
        if (alreadyPosted.isPresent()) {
            log.info("Event {} was already posted as transaction {}, skipping",
                    request.eventId(), alreadyPosted.get().getId());
            return alreadyPosted.get();
        }

        LedgerTransaction transaction;
        try {
            transaction = transactionRepository.saveAndFlush(new LedgerTransaction(
                    request.eventId(),
                    request.referenceType(),
                    request.referenceId(),
                    request.currency(),
                    request.description()));
        } catch (DataIntegrityViolationException exception) {
            // A concurrent delivery of the same event won the unique constraint. Its entries are
            // the ones that count; ours would be a duplicate posting.
            log.info("Concurrent posting for event {}, deferring to the winner", request.eventId());
            return transactionRepository.findByEventId(request.eventId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unique constraint fired but no transaction found for event " + request.eventId()));
        }

        for (PostingRequest.Line line : request.lines()) {
            LedgerAccount account = resolveAccount(
                    line.accountCode(), line.merchantId(), request.currency(), line.accountType());
            entryRepository.save(new LedgerEntry(
                    transaction.getId(), account.getId(), line.direction(), line.amount(), request.currency()));
        }

        log.info("Posted transaction {} for {} {} ({} lines)",
                transaction.getId(), request.referenceType(), request.referenceId(), request.lines().size());
        return transaction;
    }

    @Transactional(readOnly = true)
    public AccountBalance balance(String accountCode, UUID merchantId, String currency) {
        LedgerAccount account = accountRepository.find(accountCode, merchantId, currency)
                .orElseThrow(() -> new AccountNotFoundException(accountCode, merchantId, currency));

        Object[] sums = entryRepository.sumDebitsAndCredits(account.getId());
        // The query returns a single row of two aggregates; Spring Data hands it back nested.
        Object[] row = (sums.length == 1 && sums[0] instanceof Object[] inner) ? inner : sums;
        long debits = ((Number) row[0]).longValue();
        long credits = ((Number) row[1]).longValue();

        return new AccountBalance(
                accountCode,
                merchantId,
                currency,
                account.getAccountType(),
                debits,
                credits,
                account.getAccountType().normalise(debits, credits));
    }

    @Transactional(readOnly = true)
    public List<LedgerEntry> entriesForTransaction(UUID transactionId) {
        return entryRepository.findByTransactionId(transactionId);
    }

    @Transactional(readOnly = true)
    public List<LedgerTransaction> transactionsFor(String referenceType, UUID referenceId) {
        return transactionRepository.findByReferenceTypeAndReferenceIdOrderByCreatedAtAsc(
                referenceType, referenceId);
    }

    private void validate(PostingRequest request) {
        if (request.lines() == null || request.lines().size() < 2) {
            throw new UnbalancedTransactionException("A transaction needs at least one debit and one credit");
        }

        long debits = sum(request, EntryDirection.DEBIT);
        long credits = sum(request, EntryDirection.CREDIT);
        if (debits != credits) {
            throw new UnbalancedTransactionException(debits, credits);
        }
        if (debits == 0) {
            throw new UnbalancedTransactionException("A transaction cannot be for zero");
        }
        if (request.lines().stream().anyMatch(line -> line.amount() <= 0)) {
            throw new UnbalancedTransactionException("Entry amounts must be positive; direction carries the sign");
        }
    }

    private long sum(PostingRequest request, EntryDirection direction) {
        return request.lines().stream()
                .filter(line -> line.direction() == direction)
                .mapToLong(PostingRequest.Line::amount)
                .sum();
    }

    private LedgerAccount resolveAccount(String code, UUID merchantId, String currency, AccountType type) {
        return accountRepository.find(code, merchantId, currency)
                .orElseGet(() -> {
                    try {
                        return accountRepository.saveAndFlush(
                                new LedgerAccount(code, merchantId, currency, type));
                    } catch (DataIntegrityViolationException exception) {
                        // Another posting created it between our read and our write.
                        return accountRepository.find(code, merchantId, currency)
                                .orElseThrow(() -> exception);
                    }
                });
    }
}
