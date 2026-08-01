package com.openpay.fraud.api;

import com.openpay.fraud.application.FraudService;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Rule management, on the admin tier rather than the operator tier.
 *
 * <p>A rule is standing policy. Someone who can edit it can lower a threshold, wait, and raise it
 * again, which is a way to let a specific payment through that leaves no trace in the review queue.
 * That is closer in authority to issuing a key than to closing one review, so it is behind the same
 * token as issuing a key.
 */
@RestController
@RequestMapping("/internal/fraud/rules")
public class RulesController {

    private final FraudService fraudService;

    public RulesController(FraudService fraudService) {
        this.fraudService = fraudService;
    }

    /** Every rule, disabled ones included, in the order they are evaluated. */
    @GetMapping
    public List<RuleView> rules() {
        return fraudService.listRules().stream().map(RuleView::of).toList();
    }

    @PostMapping
    public ResponseEntity<RuleView> create(@Valid @RequestBody CreateRuleRequest request) {
        RuleView created = RuleView.of(fraudService.createRule(
                request.name(),
                request.ruleType(),
                request.threshold(),
                request.windowSeconds(),
                request.currency(),
                request.action(),
                request.priority()));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Rules are disabled, never deleted. A deleted rule takes with it the only explanation for every
     * decision that cites it, and {@code fraud_decisions} stores the name rather than a foreign key
     * for the same reason.
     */
    @PostMapping("/{ruleId}/disable")
    public RuleView disable(@PathVariable("ruleId") UUID ruleId) {
        return RuleView.of(fraudService.setRuleEnabled(ruleId, false));
    }

    @PostMapping("/{ruleId}/enable")
    public RuleView enable(@PathVariable("ruleId") UUID ruleId) {
        return RuleView.of(fraudService.setRuleEnabled(ruleId, true));
    }
}
