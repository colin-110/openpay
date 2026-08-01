package com.openpay.fraud.application;

import com.openpay.fraud.domain.FraudRule;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Evaluates a payment against the active rules.
 *
 * <p>First match wins, in priority order. The obvious alternative — evaluate everything and take the
 * most severe action — was rejected because it makes the policy invisible: an operator reading the
 * rule list could no longer tell what any given payment would do without simulating all of them.
 * First-match means the table reads top to bottom, and the cost of getting the order wrong is a
 * BLOCK rule shadowed by a REVIEW rule, which the rules endpoint reports rather than hides.
 *
 * <p>No state and no injected clock: velocity is asked for as a window rather than as a timestamp,
 * so the only thing this class knows about time is how long a window is.
 */
@Component
public class RuleEngine {

    public Optional<RuleMatch> firstMatch(
            List<FraudRule> rulesInPriorityOrder, ScreeningRequest request, VelocitySource velocity) {

        for (FraudRule rule : rulesInPriorityOrder) {
            if (!rule.isEnabled() || !rule.appliesTo(request.currency())) {
                continue;
            }
            Optional<String> reason = evaluate(rule, request, velocity);
            if (reason.isPresent()) {
                return Optional.of(new RuleMatch(rule, reason.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<String> evaluate(FraudRule rule, ScreeningRequest request, VelocitySource velocity) {
        return switch (rule.getRuleType()) {
            case AMOUNT_OVER -> request.amount() > rule.getThreshold()
                    ? Optional.of("Amount %d exceeds the %d %s limit"
                            .formatted(request.amount(), rule.getThreshold(), request.currency()))
                    : Optional.empty();

            // The stored count excludes the payment being screened, which has no decision row yet.
            // So "more than `threshold` in the window, counting this one" is `stored >= threshold`:
            // with a threshold of 100, the hundredth payment still passes and the next one does not.
            case VELOCITY_COUNT -> {
                Duration window = windowOf(rule);
                long recent = velocity.countForMerchant(request.merchantId(), window);
                yield recent >= rule.getThreshold()
                        ? Optional.of("Merchant screened %d payments in the last %d seconds, over the limit of %d"
                                .formatted(recent, window.toSeconds(), rule.getThreshold()))
                        : Optional.empty();
            }

            case REPEATED_AMOUNT -> {
                Duration window = windowOf(rule);
                long repeats = velocity.countForMerchantAndAmount(
                        request.merchantId(), request.amount(), window);
                yield repeats >= rule.getThreshold()
                        ? Optional.of("Amount %d seen %d times in the last %d seconds, over the limit of %d"
                                .formatted(request.amount(), repeats, window.toSeconds(), rule.getThreshold()))
                        : Optional.empty();
            }
        };
    }

    /**
     * A velocity rule without a window is a configuration error, not something to guess a default
     * for: any default is a policy nobody chose. The API refuses to create one, so reaching this is
     * a row edited directly in the database.
     */
    private Duration windowOf(FraudRule rule) {
        Integer seconds = rule.getWindowSeconds();
        if (seconds == null || seconds <= 0) {
            throw new IllegalStateException(
                    "Rule '" + rule.getName() + "' is a " + rule.getRuleType() + " rule with no window");
        }
        return Duration.ofSeconds(seconds);
    }
}
