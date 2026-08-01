package com.openpay.router.application;

import com.openpay.router.domain.RoutingRule;
import com.openpay.router.domain.RoutingRuleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the routing table from configuration the first time the service starts.
 *
 * <p>Routing used to live entirely in {@code openpay.router.providers}, and the base URLs there
 * come from environment variables that differ between a laptop, Docker Compose, and a cluster. A
 * migration cannot read those, so seeding in SQL would have hard-coded one environment's addresses
 * into every environment.
 *
 * <p>Only when the table is empty. After the first start the table is the source of truth, and the
 * configuration is inert: re-applying it on every boot would silently undo an operator's decision
 * to take an acquirer out of rotation, which is the single most likely thing to be sitting in this
 * table at 3am.
 */
@Component
public class RoutingRuleSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RoutingRuleSeeder.class);

    private final RoutingRuleRepository ruleRepository;
    private final RouterProperties properties;

    public RoutingRuleSeeder(RoutingRuleRepository ruleRepository, RouterProperties properties) {
        this.ruleRepository = ruleRepository;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (ruleRepository.count() > 0) {
            return;
        }
        if (properties.getProviders().isEmpty()) {
            log.warn("No routing rules and no configured providers: nothing can be routed.");
            return;
        }

        properties.getProviders().forEach(provider -> ruleRepository.save(new RoutingRule(
                provider.getName(),
                provider.getBaseUrl(),
                provider.getPriority(),
                null,   // general rules: every merchant,
                null,   // every currency,
                null,   // and every amount.
                null)));

        log.info("Seeded {} routing rules from configuration. The table is authoritative from now on.",
                properties.getProviders().size());
    }
}
