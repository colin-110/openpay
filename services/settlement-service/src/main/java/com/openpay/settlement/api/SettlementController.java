package com.openpay.settlement.api;

import com.openpay.settlement.application.SettlementService;
import com.openpay.settlement.domain.Settlement;
import com.openpay.settlement.domain.SettlementItem;
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

@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping("/{settlementId}")
    public Map<String, Object> get(@PathVariable("settlementId") UUID settlementId) {
        Settlement settlement = settlementService.get(settlementId);
        Map<String, Object> body = describe(settlement);
        // The payments behind the payout, which is what makes a settlement auditable.
        body.put("items", settlementService.itemsFor(settlementId).stream()
                .map(this::describeItem)
                .toList());
        return body;
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

    private Map<String, Object> describeItem(SettlementItem item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("paymentId", item.getPaymentId());
        row.put("grossAmount", item.getGrossAmount());
        row.put("feeAmount", item.getFeeAmount());
        row.put("netAmount", item.getNetAmount());
        row.put("capturedAt", item.getCapturedAt());
        return row;
    }
}
