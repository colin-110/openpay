package com.openpay.router;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.router.application.InvalidRoutingRuleException;
import com.openpay.router.application.RoutingRuleService;
import com.openpay.router.domain.RoutingRule;
import com.openpay.router.domain.RoutingRuleRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        // The seeder reads these on first start, which is what the first test asserts.
        "openpay.router.providers[0].name=seed-bank-a",
        "openpay.router.providers[0].base-url=http://seed-a.test",
        "openpay.router.providers[0].priority=10",
        "openpay.router.providers[1].name=seed-bank-b",
        "openpay.router.providers[1].base-url=http://seed-b.test",
        "openpay.router.providers[1].priority=20"
})
@Testcontainers
class RoutingRuleIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private RoutingRuleService routingRuleService;

    @Autowired
    private RoutingRuleRepository ruleRepository;

    @Test
    void anEmptyTableIsSeededFromConfigurationOnFirstStart() {
        // The base URLs come from environment variables that differ per environment, so a SQL seed
        // would have baked one environment's addresses into all of them.
        assertThat(ruleRepository.findAll())
                .extracting(RoutingRule::getProviderName)
                .contains("seed-bank-a", "seed-bank-b");
    }

    @Test
    void aGeneralRuleAppliesToEveryMerchant() {
        List<RoutingRule> candidates =
                routingRuleService.candidatesFor(UUID.randomUUID(), "USD", 10_000);

        assertThat(candidates).extracting(RoutingRule::getProviderName)
                .containsExactly("seed-bank-a", "seed-bank-b");
    }

    @Test
    void aMerchantsOwnRulesReplaceTheGeneralOnesRatherThanJoiningThem() {
        UUID merchantId = UUID.randomUUID();
        routingRuleService.createRule(
                "pinned-bank", "http://pinned.test", 5, merchantId, null, null, null);

        List<RoutingRule> candidates = routingRuleService.candidatesFor(merchantId, "USD", 10_000);

        // Merging would fail over to the general acquirers, which is exactly what pinning a
        // merchant to one acquirer is usually meant to prevent.
        assertThat(candidates).extracting(RoutingRule::getProviderName).containsExactly("pinned-bank");
    }

    @Test
    void aCurrencyRuleIsSkippedForOtherCurrencies() {
        UUID merchantId = UUID.randomUUID();
        routingRuleService.createRule(
                "eur-only-bank", "http://eur.test", 5, merchantId, "EUR", null, null);

        assertThat(routingRuleService.candidatesFor(merchantId, "EUR", 10_000))
                .extracting(RoutingRule::getProviderName).containsExactly("eur-only-bank");
        // The merchant has rules, so the general ones do not apply — and none of its own match USD.
        assertThat(routingRuleService.candidatesFor(merchantId, "USD", 10_000)).isEmpty();
    }

    @Test
    void anAmountBandIsHalfOpenSoAdjacentBandsDoNotOverlap() {
        UUID merchantId = UUID.randomUUID();
        routingRuleService.createRule(
                "small-bank", "http://small.test", 5, merchantId, null, 0L, 10_000L);
        routingRuleService.createRule(
                "large-bank", "http://large.test", 6, merchantId, null, 10_000L, null);

        assertThat(routingRuleService.candidatesFor(merchantId, "USD", 9_999))
                .extracting(RoutingRule::getProviderName).containsExactly("small-bank");
        // Exactly at the boundary: it belongs to the upper band and to nothing else.
        assertThat(routingRuleService.candidatesFor(merchantId, "USD", 10_000))
                .extracting(RoutingRule::getProviderName).containsExactly("large-bank");
    }

    @Test
    void aDisabledRuleStopsTakingNewPaymentsButStillResolvesForRefunds() {
        UUID merchantId = UUID.randomUUID();
        RoutingRule rule = routingRuleService.createRule(
                "retiring-bank", "http://retiring.test", 5, merchantId, null, null, null);

        routingRuleService.setEnabled(rule.getId(), false);

        // With no enabled rules of its own left, the merchant falls back to the platform defaults
        // rather than stopping. Disabling its only override should not take a merchant offline.
        assertThat(routingRuleService.candidatesFor(merchantId, "USD", 10_000))
                .extracting(RoutingRule::getProviderName)
                .containsExactly("seed-bank-a", "seed-bank-b");

        // Refunds go back to whoever holds the money. Taking an acquirer out of rotation must not
        // strand every refund against the payments it already took.
        assertThat(routingRuleService.baseUrlFor("retiring-bank")).contains("http://retiring.test");
    }

    @Test
    void disablingEveryGeneralRuleStopsRoutingEntirely() {
        // The other half of the fallback: when there is nothing left to fall back to, routing must
        // report that rather than quietly picking something.
        List<RoutingRule> general = ruleRepository.findByEnabledTrueAndMerchantIdIsNullOrderByPriorityAsc();
        general.forEach(rule -> routingRuleService.setEnabled(rule.getId(), false));
        try {
            assertThat(routingRuleService.candidatesFor(UUID.randomUUID(), "USD", 100)).isEmpty();
        } finally {
            general.forEach(rule -> routingRuleService.setEnabled(rule.getId(), true));
        }
    }

    @Test
    void twoRulesWithTheSameScopeAreRefused() {
        UUID merchantId = UUID.randomUUID();
        routingRuleService.createRule("dup-bank", "http://dup.test", 5, merchantId, null, null, null);

        // Two rules differing only in priority would make routing order depend on row order.
        assertThatThrownBy(() ->
                routingRuleService.createRule("dup-bank", "http://dup.test", 9, merchantId, null, null, null))
                .isInstanceOf(InvalidRoutingRuleException.class);
    }

    @Test
    void anInvertedAmountBandIsRefused() {
        assertThatThrownBy(() -> routingRuleService.createRule(
                "backwards-bank", "http://backwards.test", 5, UUID.randomUUID(), null, 10_000L, 100L))
                .isInstanceOf(InvalidRoutingRuleException.class);
    }

    @Test
    void reprioritisingChangesTheOrderPaymentsAreTriedIn() {
        UUID merchantId = UUID.randomUUID();
        RoutingRule first = routingRuleService.createRule(
                "first-bank", "http://first.test", 10, merchantId, null, null, null);
        routingRuleService.createRule(
                "second-bank", "http://second.test", 20, merchantId, null, null, null);

        assertThat(routingRuleService.candidatesFor(merchantId, "USD", 100))
                .extracting(RoutingRule::getProviderName).containsExactly("first-bank", "second-bank");

        routingRuleService.setPriority(first.getId(), 30);

        assertThat(routingRuleService.candidatesFor(merchantId, "USD", 100))
                .extracting(RoutingRule::getProviderName).containsExactly("second-bank", "first-bank");
    }
}
