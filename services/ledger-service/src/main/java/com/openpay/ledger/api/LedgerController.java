package com.openpay.ledger.api;

import com.openpay.ledger.application.AccountBalance;
import com.openpay.ledger.application.LedgerService;
import com.openpay.ledger.domain.LedgerEntry;
import com.openpay.ledger.domain.LedgerTransaction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
public class LedgerController {

    private final LedgerService ledgerService;

    public LedgerController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/accounts/{accountCode}/balance")
    public AccountBalance balance(
            @PathVariable("accountCode") String accountCode,
            @RequestParam(name = "merchantId", required = false) UUID merchantId,
            @RequestParam(name = "currency", defaultValue = "USD") String currency) {
        return ledgerService.balance(accountCode, merchantId, currency);
    }

    /** The full journal for one payment: every transaction and both sides of each. */
    @GetMapping("/entries")
    public List<Map<String, Object>> entries(
            @RequestParam("referenceId") UUID referenceId,
            @RequestParam(name = "referenceType", defaultValue = "PAYMENT") String referenceType) {

        return ledgerService.transactionsFor(referenceType, referenceId).stream()
                .map(this::describe)
                .toList();
    }

    private Map<String, Object> describe(LedgerTransaction transaction) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("transactionId", transaction.getId());
        row.put("eventId", transaction.getEventId());
        row.put("currency", transaction.getCurrency());
        row.put("description", transaction.getDescription());
        row.put("createdAt", transaction.getCreatedAt());
        row.put("lines", ledgerService.entriesForTransaction(transaction.getId()).stream()
                .map(this::describeLine)
                .toList());
        return row;
    }

    private Map<String, Object> describeLine(LedgerEntry entry) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("direction", entry.getDirection());
        line.put("amount", entry.getAmount());
        line.put("accountId", entry.getAccountId());
        return line;
    }
}
