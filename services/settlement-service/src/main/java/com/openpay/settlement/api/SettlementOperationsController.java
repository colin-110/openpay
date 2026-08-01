package com.openpay.settlement.api;

import com.openpay.settlement.application.SettlementService;
import com.openpay.settlement.domain.Settlement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing windows and marking payouts complete: platform-operator actions, on their own path so
 * the merchant-facing prefix can be authenticated by merchant credentials without a POST hiding
 * underneath it that decides when money moves.
 */
@RestController
@RequestMapping("/internal/settlements")
public class SettlementOperationsController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SettlementService settlementService;

    public SettlementOperationsController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    /** Every merchant's payouts, for an operator looking across the platform. */
    @GetMapping
    public List<Map<String, Object>> all(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return settlementService
                .listAll(org.springframework.data.domain.PageRequest.of(
                        Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE)))
                .map(this::describe)
                .getContent();
    }

    /** Triggers a run explicitly. Useful for a demo, and how an operator would force a window. */
    @PostMapping("/run")
    public List<Map<String, Object>> run(
            @RequestParam(name = "date", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate settlementDate = date != null ? date : LocalDate.now(ZoneOffset.UTC);
        return settlementService.runSettlement(settlementDate).stream()
                .map(this::describe)
                .toList();
    }

    @PostMapping("/{settlementId}/complete")
    public Map<String, Object> complete(@PathVariable("settlementId") UUID settlementId) {
        return describe(settlementService.complete(settlementId));
    }

    private Map<String, Object> describe(Settlement settlement) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", settlement.getId());
        row.put("merchantId", settlement.getMerchantId());
        row.put("currency", settlement.getCurrency());
        row.put("settlementDate", settlement.getSettlementDate());
        row.put("grossAmount", settlement.getGrossAmount());
        row.put("feeAmount", settlement.getFeeAmount());
        row.put("netAmount", settlement.getNetAmount());
        row.put("itemCount", settlement.getItemCount());
        row.put("status", settlement.getStatus());
        row.put("createdAt", settlement.getCreatedAt());
        return row;
    }
}
