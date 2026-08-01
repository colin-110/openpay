package com.openpay.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.events.OpenPayTopics;
import com.openpay.fraud.application.FraudService;
import com.openpay.fraud.application.InvalidRuleException;
import com.openpay.fraud.application.ReviewNotOpenException;
import com.openpay.fraud.application.ScreeningRequest;
import com.openpay.fraud.domain.DecisionOutcome;
import com.openpay.fraud.domain.FraudDecision;
import com.openpay.fraud.domain.RuleAction;
import com.openpay.fraud.domain.RuleType;
import com.openpay.outbox.OutboxEvent;
import com.openpay.outbox.OutboxRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs against the seeded rules from V1, because those are what a fresh deployment actually has and
 * a test that replaces them first would not notice if the seed were wrong.
 */
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=false",
        "openpay.outbox.relay-enabled=false"
})
@Testcontainers
class FraudScreeningIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private FraudService fraudService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void anOrdinaryPaymentIsAllowed() {
        FraudDecision decision = fraudService.screen(request(50_00));

        assertThat(decision.getOutcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(decision.getRuleName()).isNull();
    }

    @Test
    void aHighValuePaymentIsHeldForReview() {
        FraudDecision decision = fraudService.screen(request(60_000_00));

        assertThat(decision.getOutcome()).isEqualTo(DecisionOutcome.REVIEW);
        assertThat(decision.getRuleName()).isEqualTo("high-value-payment");
    }

    @Test
    void anExtremeValuePaymentIsBlockedOutright() {
        FraudDecision decision = fraudService.screen(request(600_000_00));

        // Both amount rules match; the BLOCK one is at the lower priority number.
        assertThat(decision.getOutcome()).isEqualTo(DecisionOutcome.BLOCK);
        assertThat(decision.getRuleName()).isEqualTo("extreme-value-payment");
    }

    @Test
    void screeningTheSamePaymentTwiceReturnsTheFirstAnswer() {
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        ScreeningRequest request = new ScreeningRequest(paymentId, merchantId, 100_00, "INR", "CARD");

        FraudDecision first = fraudService.screen(request);
        FraudDecision second = fraudService.screen(request);

        // Payment creation retries. Re-running the rules would evaluate against a velocity window
        // that has moved, so the same payment could be allowed once and blocked the next time.
        assertThat(second.getId()).isEqualTo(first.getId());
        // And a replay must not announce the decision a second time.
        assertThat(completionEventsFor(paymentId)).hasSize(1);
    }

    @Test
    void aFinalDecisionPublishesItsOutcomeImmediately() {
        UUID paymentId = UUID.randomUUID();
        fraudService.screen(new ScreeningRequest(paymentId, UUID.randomUUID(), 100_00, "INR", "CARD"));

        assertThat(completionEventsFor(paymentId)).hasSize(1);
    }

    @Test
    void aHeldPaymentPublishesNothingUntilAHumanDecides() {
        UUID paymentId = UUID.randomUUID();
        fraudService.screen(new ScreeningRequest(paymentId, UUID.randomUUID(), 60_000_00, "INR", "CARD"));

        // REVIEW is not an answer, so there is nothing to tell payment-service yet.
        assertThat(completionEventsFor(paymentId)).isEmpty();

        fraudService.resolveReview(paymentId, DecisionOutcome.ALLOW, "risk-analyst@openpay.test");

        assertThat(completionEventsFor(paymentId)).hasSize(1);
    }

    @Test
    void resolvingAReviewKeepsTheOriginalJudgement() {
        UUID paymentId = UUID.randomUUID();
        fraudService.screen(new ScreeningRequest(paymentId, UUID.randomUUID(), 60_000_00, "INR", "CARD"));

        FraudDecision resolved = fraudService.resolveReview(
                paymentId, DecisionOutcome.BLOCK, "risk-analyst@openpay.test");

        // How it was first judged and what an operator did about it are two different facts.
        assertThat(resolved.getOutcome()).isEqualTo(DecisionOutcome.REVIEW);
        assertThat(resolved.getResolvedOutcome()).isEqualTo(DecisionOutcome.BLOCK);
        assertThat(resolved.effectiveOutcome()).isEqualTo(DecisionOutcome.BLOCK);
        assertThat(resolved.getResolvedBy()).isEqualTo("risk-analyst@openpay.test");
    }

    @Test
    void aReviewCannotBeResolvedTwice() {
        UUID paymentId = UUID.randomUUID();
        fraudService.screen(new ScreeningRequest(paymentId, UUID.randomUUID(), 60_000_00, "INR", "CARD"));
        fraudService.resolveReview(paymentId, DecisionOutcome.ALLOW, "first@openpay.test");

        // A second resolution would publish a second release for a payment that has already moved on.
        assertThatThrownBy(() ->
                fraudService.resolveReview(paymentId, DecisionOutcome.BLOCK, "second@openpay.test"))
                .isInstanceOf(ReviewNotOpenException.class);
    }

    @Test
    void aDecisionThatWasNeverAReviewCannotBeResolved() {
        UUID paymentId = UUID.randomUUID();
        fraudService.screen(new ScreeningRequest(paymentId, UUID.randomUUID(), 100_00, "INR", "CARD"));

        assertThatThrownBy(() ->
                fraudService.resolveReview(paymentId, DecisionOutcome.BLOCK, "analyst@openpay.test"))
                .isInstanceOf(ReviewNotOpenException.class);
    }

    @Test
    void anOpenReviewAppearsInTheQueueAndLeavesWhenResolved() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        fraudService.screen(new ScreeningRequest(paymentId, merchantId, 60_000_00, "INR", "CARD"));

        assertThat(fraudService.openReviews(merchantId))
                .extracting(FraudDecision::getPaymentId)
                .containsExactly(paymentId);

        fraudService.resolveReview(paymentId, DecisionOutcome.ALLOW, "analyst@openpay.test");

        assertThat(fraudService.openReviews(merchantId)).isEmpty();
    }

    @Test
    void repeatingOneAmountTripsTheCardTestingRule() {
        UUID merchantId = UUID.randomUUID();
        long amount = 999_00;

        // The seeded rule blocks the eleventh identical amount within five minutes.
        for (int i = 0; i < 10; i++) {
            assertThat(fraudService.screen(
                            new ScreeningRequest(UUID.randomUUID(), merchantId, amount, "INR", "CARD"))
                    .getOutcome())
                    .isEqualTo(DecisionOutcome.ALLOW);
        }

        FraudDecision eleventh = fraudService.screen(
                new ScreeningRequest(UUID.randomUUID(), merchantId, amount, "INR", "CARD"));

        assertThat(eleventh.getOutcome()).isEqualTo(DecisionOutcome.BLOCK);
        assertThat(eleventh.getRuleName()).isEqualTo("repeated-identical-amount");
    }

    @Test
    void oneMerchantsTrafficDoesNotScoreAgainstAnother() {
        UUID noisy = UUID.randomUUID();
        UUID quiet = UUID.randomUUID();
        long amount = 777_00;
        for (int i = 0; i < 12; i++) {
            fraudService.screen(new ScreeningRequest(UUID.randomUUID(), noisy, amount, "INR", "CARD"));
        }

        FraudDecision innocent = fraudService.screen(
                new ScreeningRequest(UUID.randomUUID(), quiet, amount, "INR", "CARD"));

        assertThat(innocent.getOutcome()).isEqualTo(DecisionOutcome.ALLOW);
    }

    @Test
    void anAmountRuleWithoutACurrencyIsRefused() {
        assertThatThrownBy(() -> fraudService.createRule(
                "no-currency", RuleType.AMOUNT_OVER, 1_000, null, null, RuleAction.BLOCK, 5))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining("currency");
    }

    @Test
    void aVelocityRuleWithoutAWindowIsRefused() {
        assertThatThrownBy(() -> fraudService.createRule(
                "no-window", RuleType.VELOCITY_COUNT, 10, null, null, RuleAction.REVIEW, 5))
                .isInstanceOf(InvalidRuleException.class)
                .hasMessageContaining("windowSeconds");
    }

    @Test
    void aDisabledRuleStopsMatching() {
        UUID ruleId = fraudService.listRules().stream()
                .filter(rule -> rule.getName().equals("high-value-payment"))
                .findFirst()
                .orElseThrow()
                .getId();

        fraudService.setRuleEnabled(ruleId, false);
        try {
            assertThat(fraudService.screen(request(60_000_00)).getOutcome())
                    .isEqualTo(DecisionOutcome.ALLOW);
        } finally {
            fraudService.setRuleEnabled(ruleId, true);
        }
    }

    private ScreeningRequest request(long amount) {
        return new ScreeningRequest(UUID.randomUUID(), UUID.randomUUID(), amount, "INR", "CARD");
    }

    private List<OutboxEvent> completionEventsFor(UUID paymentId) {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals(OpenPayTopics.FRAUD_CHECK_COMPLETED))
                .filter(event -> event.getAggregateId().equals(paymentId.toString()))
                .toList();
    }
}
