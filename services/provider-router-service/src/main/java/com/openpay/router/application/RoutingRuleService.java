package com.openpay.router.application;

import com.openpay.router.domain.RoutingRule;
import com.openpay.router.domain.RoutingRuleRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and edits the routing table. */
@Service
public class RoutingRuleService {

    private static final Logger log = LoggerFactory.getLogger(RoutingRuleService.class);

    private final RoutingRuleRepository ruleRepository;

    public RoutingRuleService(RoutingRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    /**
     * The acquirers to try for one payment, in order.
     *
     * <p>A merchant's own rules <em>replace</em> the general ones rather than merging with them.
     * Merging was the obvious alternative and it is quietly wrong: an operator who pins one
     * merchant to one acquirer usually means "and not the other one", and a merged list would fail
     * over to exactly the acquirer they were steering away from. Replacement makes the override say
     * what it looks like it says — at the cost that a merchant rule must list every acquirer that
     * merchant may use, which is the more honest thing to have to write down.
     *
     * <p>"When a merchant has rules" means enabled ones. Disabling a merchant's only override drops
     * them back to the platform defaults rather than taking them offline — switching a rule off
     * should never be the thing that stops a merchant taking payments.
     *
     * <p>Read on every payment rather than cached. It is one indexed query against a table with a
     * handful of rows, next to an HTTP call to a bank; a cache here would buy nothing and would
     * mean an operator taking an acquirer out of rotation had to wait for a TTL to find out
     * whether it worked.
     */
    @Transactional(readOnly = true)
    public List<RoutingRule> candidatesFor(UUID merchantId, String currency, long amount) {
        List<RoutingRule> merchantRules =
                ruleRepository.findByEnabledTrueAndMerchantIdOrderByPriorityAsc(merchantId);
        List<RoutingRule> applicable = merchantRules.isEmpty()
                ? ruleRepository.findByEnabledTrueAndMerchantIdIsNullOrderByPriorityAsc()
                : merchantRules;

        return applicable.stream().filter(rule -> rule.matches(currency, amount)).toList();
    }

    @Transactional(readOnly = true)
    public List<RoutingRule> listRules() {
        return ruleRepository.findAllByOrderByPriorityAsc();
    }

    /**
     * Where to reach an acquirer by name.
     *
     * <p>Disabled rules count here, and that is the point. Disabling a rule means "send no new
     * payments to this acquirer"; it does not mean "we can no longer reverse the payments it
     * already took". Refunds go back to whoever captured the money, and taking an acquirer out of
     * rotation must not strand every refund against it.
     */
    @Transactional(readOnly = true)
    public Optional<String> baseUrlFor(String providerName) {
        return ruleRepository.findByProviderNameOrderByPriorityAsc(providerName).stream()
                .map(RoutingRule::getBaseUrl)
                .findFirst();
    }

    @Transactional
    public RoutingRule createRule(
            String providerName,
            String baseUrl,
            int priority,
            UUID merchantId,
            String currency,
            Long minAmount,
            Long maxAmount) {

        if (minAmount != null && maxAmount != null && minAmount >= maxAmount) {
            throw new InvalidRoutingRuleException("minAmount must be below maxAmount");
        }
        try {
            RoutingRule rule = ruleRepository.saveAndFlush(new RoutingRule(
                    providerName, baseUrl, priority, merchantId, currency, minAmount, maxAmount));
            log.info("Created routing rule for {} at priority {} (merchant={}, currency={})",
                    providerName, priority, merchantId, currency);
            return rule;
        } catch (DataIntegrityViolationException exception) {
            // Two rules differing only in priority would make routing order depend on row order.
            throw new InvalidRoutingRuleException(
                    "A rule for " + providerName + " with that exact scope already exists");
        }
    }

    /**
     * Takes an acquirer out of rotation, or puts it back.
     *
     * <p>The reason this table exists. Before it, doing this meant editing configuration and
     * restarting the router, which is a slow answer to an acquirer having a bad afternoon.
     */
    @Transactional
    public RoutingRule setEnabled(UUID ruleId, boolean enabled) {
        RoutingRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RoutingRuleNotFoundException(ruleId));
        rule.setEnabled(enabled);
        log.warn("Routing rule for {} is now {}", rule.getProviderName(), enabled ? "enabled" : "disabled");
        return rule;
    }

    @Transactional
    public RoutingRule setPriority(UUID ruleId, int priority) {
        RoutingRule rule = ruleRepository.findById(ruleId)
                .orElseThrow(() -> new RoutingRuleNotFoundException(ruleId));
        rule.setPriority(priority);
        log.info("Routing rule for {} moved to priority {}", rule.getProviderName(), priority);
        return rule;
    }
}
