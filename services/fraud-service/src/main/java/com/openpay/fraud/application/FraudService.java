package com.openpay.fraud.application;

import com.openpay.events.OpenPayTopics;
import com.openpay.events.payload.FraudCheckCompleted;
import com.openpay.events.payload.FraudCheckRequested;
import com.openpay.fraud.domain.DecisionOutcome;
import com.openpay.fraud.domain.FraudDecision;
import com.openpay.fraud.domain.FraudDecisionRepository;
import com.openpay.fraud.domain.FraudRule;
import com.openpay.fraud.domain.FraudRuleRepository;
import com.openpay.fraud.domain.RuleAction;
import com.openpay.fraud.domain.RuleType;
import com.openpay.outbox.OutboxWriter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FraudService {

    private static final Logger log = LoggerFactory.getLogger(FraudService.class);
    private static final String AGGREGATE_TYPE = "fraud-decision";

    private final FraudRuleRepository ruleRepository;
    private final FraudDecisionRepository decisionRepository;
    private final RuleEngine ruleEngine;
    private final OutboxWriter outboxWriter;
    private final FraudProperties properties;
    private final MeterRegistry meterRegistry;

    public FraudService(
            FraudRuleRepository ruleRepository,
            FraudDecisionRepository decisionRepository,
            RuleEngine ruleEngine,
            OutboxWriter outboxWriter,
            FraudProperties properties,
            MeterRegistry meterRegistry) {
        this.ruleRepository = ruleRepository;
        this.decisionRepository = decisionRepository;
        this.ruleEngine = ruleEngine;
        this.outboxWriter = outboxWriter;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Screens one payment.
     *
     * <p>Idempotent by payment id, and it has to be: payment creation retries, and re-running the
     * rules would evaluate against a velocity window that has moved since. A second call returns the
     * first answer, unchanged, and does not publish a second event.
     */
    @Transactional
    public FraudDecision screen(ScreeningRequest request) {
        Optional<FraudDecision> existing = decisionRepository.findByPaymentId(request.paymentId());
        if (existing.isPresent()) {
            log.info("Replaying stored decision {} for payment {}",
                    existing.get().effectiveOutcome(), request.paymentId());
            return existing.get();
        }

        List<FraudRule> rules = ruleRepository.findByEnabledTrueOrderByPriorityAsc();
        Optional<RuleMatch> match = ruleEngine.firstMatch(rules, request, velocitySource());

        DecisionOutcome outcome = match
                .map(hit -> hit.rule().getAction() == RuleAction.BLOCK
                        ? DecisionOutcome.BLOCK
                        : DecisionOutcome.REVIEW)
                .orElse(DecisionOutcome.ALLOW);

        FraudDecision decision = new FraudDecision(
                request.paymentId(),
                request.merchantId(),
                request.amount(),
                request.currency(),
                request.paymentMethodType(),
                outcome,
                match.map(hit -> hit.rule().getName()).orElse(null),
                match.map(RuleMatch::reason).orElse(null));

        try {
            decisionRepository.saveAndFlush(decision);
        } catch (DataIntegrityViolationException exception) {
            // A concurrent screen of the same payment won the unique constraint on payment_id.
            // Its answer is as good as this one, and it has already published the event.
            log.info("Concurrent screening of payment {}, returning the stored decision", request.paymentId());
            return decisionRepository.findByPaymentId(request.paymentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unique constraint fired but no decision found for payment " + request.paymentId()));
        }

        // Same transaction as the decision row, so a held payment can never exist without the event
        // that will eventually release it.
        outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.FRAUD_CHECK_REQUESTED, request.paymentId(),
                new FraudCheckRequested(
                        request.paymentId(),
                        request.merchantId(),
                        request.amount(),
                        request.currency(),
                        request.paymentMethodType()));

        if (outcome.isFinal()) {
            publishCompletion(decision, outcome, false);
        }

        countDecision(outcome, decision.getRuleName());
        log.info("Screened payment {} for merchant {}: {}{}",
                request.paymentId(), request.merchantId(), outcome,
                decision.getRuleName() == null ? "" : " on rule '" + decision.getRuleName() + "'");
        return decision;
    }

    /**
     * Closes an open review.
     *
     * <p>This is the only thing that releases a held payment, which is why it publishes rather than
     * calling payment-service back: the release has to survive payment-service being down at the
     * moment an operator clicks the button.
     */
    @Transactional
    public FraudDecision resolveReview(UUID paymentId, DecisionOutcome finalOutcome, String operator) {
        FraudDecision decision = decisionRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new DecisionNotFoundException(paymentId));

        decision.resolve(finalOutcome, operator);
        publishCompletion(decision, finalOutcome, true);

        meterRegistry.counter("openpay.fraud.reviews.resolved",
                "outcome", finalOutcome.name()).increment();
        log.info("Review for payment {} resolved as {} by {}", paymentId, finalOutcome, operator);
        return decision;
    }

    @Transactional(readOnly = true)
    public List<FraudDecision> openReviews(UUID merchantId) {
        return decisionRepository.findOpenReviews(merchantId, PageRequest.of(0, properties.getReviewPageSize()));
    }

    @Transactional(readOnly = true)
    public FraudDecision decisionFor(UUID paymentId) {
        return decisionRepository.findByPaymentId(paymentId)
                .orElseThrow(() -> new DecisionNotFoundException(paymentId));
    }

    @Transactional(readOnly = true)
    public List<FraudRule> listRules() {
        return ruleRepository.findAllByOrderByPriorityAsc();
    }

    @Transactional
    public FraudRule createRule(
            String name,
            RuleType type,
            long threshold,
            Integer windowSeconds,
            String currency,
            RuleAction action,
            int priority) {

        validate(type, threshold, windowSeconds, currency);
        if (ruleRepository.findByName(name).isPresent()) {
            throw new InvalidRuleException("A rule named '" + name + "' already exists");
        }
        FraudRule rule = ruleRepository.save(
                new FraudRule(name, type, threshold, windowSeconds, currency, action, priority));
        log.info("Created fraud rule '{}' ({} {} -> {}) at priority {}",
                name, type, threshold, action, priority);
        return rule;
    }

    @Transactional
    public FraudRule setRuleEnabled(UUID ruleId, boolean enabled) {
        FraudRule rule = ruleRepository.findById(ruleId).orElseThrow(() -> new RuleNotFoundException(ruleId));
        rule.setEnabled(enabled);
        log.info("Fraud rule '{}' is now {}", rule.getName(), enabled ? "enabled" : "disabled");
        return rule;
    }

    /** How many payments are sitting in the queue. Exposed as a gauge for alerting. */
    @Transactional(readOnly = true)
    public long openReviewCount() {
        return decisionRepository.countByOutcomeAndResolvedOutcomeIsNull(DecisionOutcome.REVIEW);
    }

    private void publishCompletion(FraudDecision decision, DecisionOutcome outcome, boolean fromReview) {
        outboxWriter.append(AGGREGATE_TYPE, OpenPayTopics.FRAUD_CHECK_COMPLETED, decision.getPaymentId(),
                new FraudCheckCompleted(
                        decision.getPaymentId(),
                        decision.getMerchantId(),
                        outcome.name(),
                        decision.getRuleName(),
                        decision.getReason(),
                        fromReview));
    }

    private void countDecision(DecisionOutcome outcome, String ruleName) {
        Counter.builder("openpay.fraud.decisions")
                .tag("outcome", outcome.name())
                // A constant tag value for the allow path, rather than the rule name, so the
                // cardinality of this metric is bounded by the number of rules and not by traffic.
                .tag("rule", ruleName == null ? "none" : ruleName)
                .register(meterRegistry)
                .increment();
    }

    private void validate(RuleType type, long threshold, Integer windowSeconds, String currency) {
        if (threshold <= 0) {
            throw new InvalidRuleException("threshold must be positive");
        }
        boolean needsWindow = type == RuleType.VELOCITY_COUNT || type == RuleType.REPEATED_AMOUNT;
        if (needsWindow && (windowSeconds == null || windowSeconds <= 0)) {
            throw new InvalidRuleException(type + " needs a positive windowSeconds");
        }
        if (!needsWindow && windowSeconds != null) {
            throw new InvalidRuleException(type + " does not use a window; leave windowSeconds unset");
        }
        // An amount threshold without a currency would compare paise against cents. The velocity
        // rules count payments rather than money, so they are free to span currencies.
        if (type == RuleType.AMOUNT_OVER && (currency == null || currency.isBlank())) {
            throw new InvalidRuleException("AMOUNT_OVER needs a currency; a minor-unit threshold "
                    + "means nothing without one");
        }
    }

    private VelocitySource velocitySource() {
        return new VelocitySource() {
            @Override
            public long countForMerchant(UUID merchantId, Duration window) {
                return decisionRepository.countByMerchantIdAndCreatedAtAfter(merchantId, since(window));
            }

            @Override
            public long countForMerchantAndAmount(UUID merchantId, long amount, Duration window) {
                return decisionRepository.countByMerchantIdAndAmountAndCreatedAtAfter(
                        merchantId, amount, since(window));
            }

            private OffsetDateTime since(Duration window) {
                return OffsetDateTime.now().minus(window);
            }
        };
    }
}
