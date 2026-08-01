package com.openpay.router.api;

import com.openpay.router.application.RoutingRuleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The routing table, on the admin tier.
 *
 * <p>On its own prefix rather than under {@code /internal/router}, which is guarded by the service
 * token: a path covered by both filters would need both credentials, which is a confusing way to
 * express "operators only".
 *
 * <p>Admin rather than ops, unlike the rest of this service. Editing a rule decides where every
 * payment on the platform goes — it can send all traffic to one acquirer, or to a base URL of the
 * editor's choosing. That is closer in authority to issuing a credential than to reading a report,
 * and it is the same reasoning that puts the fraud rules behind the same token.
 */
@RestController
@RequestMapping("/internal/routing-rules")
public class RoutingRulesController {

    private final RoutingRuleService routingRuleService;

    public RoutingRulesController(RoutingRuleService routingRuleService) {
        this.routingRuleService = routingRuleService;
    }

    /** Every rule, disabled ones included, in the order they are evaluated. */
    @GetMapping
    public List<RoutingRuleView> rules() {
        return routingRuleService.listRules().stream().map(RoutingRuleView::of).toList();
    }

    /** What would be tried for a hypothetical payment. The cheapest way to check a change. */
    @GetMapping("/resolve")
    public List<RoutingRuleView> resolve(
            @RequestParam("merchantId") UUID merchantId,
            @RequestParam("currency") String currency,
            @RequestParam("amount") long amount) {
        return routingRuleService.candidatesFor(merchantId, currency, amount).stream()
                .map(RoutingRuleView::of)
                .toList();
    }

    @PostMapping
    public ResponseEntity<RoutingRuleView> create(@Valid @RequestBody CreateRoutingRuleRequest request) {
        RoutingRuleView created = RoutingRuleView.of(routingRuleService.createRule(
                request.providerName(),
                request.baseUrl(),
                request.priority(),
                request.merchantId(),
                request.currency(),
                request.minAmount(),
                request.maxAmount()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Takes an acquirer out of rotation. The reason this table exists at all: before it, this was
     * a config change and a restart.
     */
    @PostMapping("/{ruleId}/disable")
    public RoutingRuleView disable(@PathVariable("ruleId") UUID ruleId) {
        return RoutingRuleView.of(routingRuleService.setEnabled(ruleId, false));
    }

    @PostMapping("/{ruleId}/enable")
    public RoutingRuleView enable(@PathVariable("ruleId") UUID ruleId) {
        return RoutingRuleView.of(routingRuleService.setEnabled(ruleId, true));
    }

    @PostMapping("/{ruleId}/priority")
    public RoutingRuleView reprioritise(
            @PathVariable("ruleId") UUID ruleId, @RequestParam("priority") int priority) {
        return RoutingRuleView.of(routingRuleService.setPriority(ruleId, priority));
    }
}
