package com.openpay.fraud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.fraud.domain.FraudRule;
import com.openpay.fraud.domain.RuleAction;
import com.openpay.fraud.domain.RuleType;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RuleEngineTest {

    private static final UUID MERCHANT = UUID.randomUUID();

    private final RuleEngine engine = new RuleEngine();

    @Test
    void allowsAPaymentNothingMatches() {
        List<FraudRule> rules = List.of(amountOver("big", 100_000, RuleAction.BLOCK, 10));

        assertThat(engine.firstMatch(rules, payment(500), noTraffic())).isEmpty();
    }

    @Test
    void matchesAnAmountStrictlyOverTheThreshold() {
        List<FraudRule> rules = List.of(amountOver("big", 100_000, RuleAction.BLOCK, 10));

        // The threshold itself is allowed. "Over 1000" must not refuse a payment of exactly 1000.
        assertThat(engine.firstMatch(rules, payment(100_000), noTraffic())).isEmpty();
        assertThat(engine.firstMatch(rules, payment(100_001), noTraffic())).isPresent();
    }

    @Test
    void theLowestPriorityNumberWinsEvenWhenBothMatch() {
        List<FraudRule> rules = List.of(
                amountOver("block-extreme", 100_000, RuleAction.BLOCK, 10),
                amountOver("review-high", 10_000, RuleAction.REVIEW, 20));

        RuleMatch match = engine.firstMatch(rules, payment(200_000), noTraffic()).orElseThrow();

        // Both match. An eight-figure payment should be refused, not queued behind the review rule.
        assertThat(match.rule().getName()).isEqualTo("block-extreme");
    }

    @Test
    void aRuleForAnotherCurrencyIsSkipped() {
        FraudRule inrOnly = new FraudRule(
                "inr-high", RuleType.AMOUNT_OVER, 1_000, null, "INR", RuleAction.BLOCK, 10);

        // 5000 cents is not 5000 paise, and comparing them would be a policy nobody wrote.
        assertThat(engine.firstMatch(List.of(inrOnly), payment(5_000, "USD"), noTraffic())).isEmpty();
        assertThat(engine.firstMatch(List.of(inrOnly), payment(5_000, "INR"), noTraffic())).isPresent();
    }

    @Test
    void aDisabledRuleIsSkipped() {
        FraudRule rule = amountOver("big", 100, RuleAction.BLOCK, 10);
        rule.setEnabled(false);

        assertThat(engine.firstMatch(List.of(rule), payment(999_999), noTraffic())).isEmpty();
    }

    @Test
    void velocityCountsThePaymentBeingScreened() {
        FraudRule rule = new FraudRule(
                "burst", RuleType.VELOCITY_COUNT, 3, 60, null, RuleAction.REVIEW, 10);

        // Three stored plus this one is four, which is over a limit of three. Two stored is not.
        assertThat(engine.firstMatch(List.of(rule), payment(100), merchantCount(2))).isEmpty();
        assertThat(engine.firstMatch(List.of(rule), payment(100), merchantCount(3))).isPresent();
    }

    @Test
    void repeatedAmountLooksAtTheSameAmountOnly() {
        FraudRule rule = new FraudRule(
                "card-testing", RuleType.REPEATED_AMOUNT, 5, 300, null, RuleAction.BLOCK, 10);

        VelocitySource busyButVaried = new VelocitySource() {
            @Override
            public long countForMerchant(UUID merchantId, Duration window) {
                return 1_000;
            }

            @Override
            public long countForMerchantAndAmount(UUID merchantId, long amount, Duration window) {
                return 1;
            }
        };

        // A merchant doing real volume is not card testing. The signature is repetition of one
        // amount, which is why this rule cannot be expressed as a plain velocity count.
        assertThat(engine.firstMatch(List.of(rule), payment(100), busyButVaried)).isEmpty();
    }

    @Test
    void aVelocityRuleWithNoWindowIsRefusedRatherThanGuessed() {
        FraudRule broken = new FraudRule(
                "no-window", RuleType.VELOCITY_COUNT, 5, null, null, RuleAction.REVIEW, 10);

        assertThatThrownBy(() -> engine.firstMatch(List.of(broken), payment(100), merchantCount(50)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no window");
    }

    @Test
    void theReasonNamesWhatWasActuallySeen() {
        List<FraudRule> rules = List.of(amountOver("big", 1_000, RuleAction.BLOCK, 10));

        RuleMatch match = engine.firstMatch(rules, payment(9_999), noTraffic()).orElseThrow();

        // The reason ends up on the decision and in the event, so it has to stand alone.
        assertThat(match.reason()).contains("9999").contains("1000").contains("INR");
    }

    private static FraudRule amountOver(String name, long threshold, RuleAction action, int priority) {
        return new FraudRule(name, RuleType.AMOUNT_OVER, threshold, null, "INR", action, priority);
    }

    private static ScreeningRequest payment(long amount) {
        return payment(amount, "INR");
    }

    private static ScreeningRequest payment(long amount, String currency) {
        return new ScreeningRequest(UUID.randomUUID(), MERCHANT, amount, currency, "CARD");
    }

    private static VelocitySource noTraffic() {
        return merchantCount(0);
    }

    private static VelocitySource merchantCount(long count) {
        return new VelocitySource() {
            @Override
            public long countForMerchant(UUID merchantId, Duration window) {
                return count;
            }

            @Override
            public long countForMerchantAndAmount(UUID merchantId, long amount, Duration window) {
                return count;
            }
        };
    }
}
