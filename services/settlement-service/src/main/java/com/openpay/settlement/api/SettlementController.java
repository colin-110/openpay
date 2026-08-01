package com.openpay.settlement.api;

import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import com.openpay.settlement.application.SettlementNotFoundException;
import com.openpay.settlement.application.SettlementService;
import com.openpay.settlement.domain.Settlement;
import com.openpay.settlement.domain.SettlementItem;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A merchant's own payouts.
 *
 * <p>Read-only, and scoped to the caller's merchant on every query. Closing a window and marking a
 * payout complete are operator actions and live on {@code /internal/settlements} instead: a
 * merchant should be able to see when it gets paid without being able to decide when.
 */
@RestController
@RequestMapping("/api/v1/settlements")
public class SettlementController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SettlementService settlementService;

    public SettlementController(SettlementService settlementService) {
        this.settlementService = settlementService;
    }

    @GetMapping
    public Map<String, Object> list(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE));
        var found = settlementService.listForMerchant(principal.merchantId(), pageable);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", found.map(this::describe).getContent());
        body.put("page", found.getNumber());
        body.put("size", found.getSize());
        body.put("totalItems", found.getTotalElements());
        body.put("totalPages", found.getTotalPages());
        return body;
    }

    @GetMapping("/{settlementId}")
    public Map<String, Object> get(
            @RequestAttribute(ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE) ApiKeyPrincipal principal,
            @PathVariable("settlementId") UUID settlementId) {

        Settlement settlement = settlementService.get(settlementId);
        // Not-found rather than forbidden: another merchant's payout does not exist as far as this
        // caller is concerned, and saying otherwise confirms it is real.
        if (!settlement.getMerchantId().equals(principal.merchantId())) {
            throw new SettlementNotFoundException(settlementId);
        }

        Map<String, Object> body = describe(settlement);
        // The payments behind the payout, which is what makes a settlement auditable.
        body.put("items", settlementService.itemsFor(settlementId).stream()
                .map(this::describeItem)
                .toList());
        return body;
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
