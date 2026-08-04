package com.openpay.fraud.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openpay.fraud.domain.FraudDecisionRepository;
import com.openpay.fraud.domain.FraudRuleRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * The warm-up is a performance component whose failure mode is a <em>correctness</em> one: when the
 * screening path is cold, the caller's 1-second timeout expires and the payment is recorded
 * {@code UNSCREENED} — accepted, captured, and never checked against a single rule.
 *
 * <p>So the tests here are about the two things that would silently reopen that window: the
 * periodic sweep not running, and a failed sweep taking the schedule down with it.
 */
@ExtendWith(MockitoExtension.class)
class ScreeningWarmUpTest {

    @Mock
    private FraudRuleRepository ruleRepository;

    @Mock
    private FraudDecisionRepository decisionRepository;

    @Test
    void warmsBothQueriesAtStartup() {
        ScreeningWarmUp warmUp = new ScreeningWarmUp(ruleRepository, decisionRepository, true);

        warmUp.run(new DefaultApplicationArguments());

        // Both, not either: screening reads the rules *and* checks for an existing decision, and a
        // warm-up that only touched one would leave the other to be paid for by a real payment.
        verify(ruleRepository).findByEnabledTrueOrderByPriorityAsc();
        verify(decisionRepository).findByPaymentId(any(UUID.class));
    }

    @Test
    void keepsWarmingAfterStartup() {
        ScreeningWarmUp warmUp = new ScreeningWarmUp(ruleRepository, decisionRepository, true);

        warmUp.run(new DefaultApplicationArguments());
        warmUp.keepWarm();
        warmUp.keepWarm();

        // The whole point of the scheduled sweep. A startup-only warm-up left the path to go cold
        // again whenever the platform was simply unused, and measured against a real stack every
        // unscreened payment followed an idle gap — two and a half minutes was enough.
        verify(ruleRepository, times(3)).findByEnabledTrueOrderByPriorityAsc();
        verify(decisionRepository, times(3)).findByPaymentId(any(UUID.class));
    }

    @Test
    void probesForAPaymentThatCannotExist() {
        ScreeningWarmUp warmUp = new ScreeningWarmUp(ruleRepository, decisionRepository, true);

        warmUp.keepWarm();

        // A random UUID rather than a real payment id, so the probe is a genuine index lookup that
        // returns nothing and cannot disturb a decision that actually matters.
        verify(decisionRepository).findByPaymentId(any(UUID.class));
        verify(decisionRepository, never()).save(any());
    }

    @Test
    void doesNothingAtAllWhenDisabled() {
        ScreeningWarmUp warmUp = new ScreeningWarmUp(ruleRepository, decisionRepository, false);

        warmUp.run(new DefaultApplicationArguments());
        warmUp.keepWarm();

        verifyNoInteractions(ruleRepository, decisionRepository);
    }

    @Test
    void aFailedSweepIsSwallowedRatherThanEndingTheSchedule() {
        when(ruleRepository.findByEnabledTrueOrderByPriorityAsc())
                .thenThrow(new IllegalStateException("database is unreachable"));
        ScreeningWarmUp warmUp = new ScreeningWarmUp(ruleRepository, decisionRepository, true);

        // Spring's scheduler abandons a fixed-delay task whose method throws. Letting this escape
        // would mean one blip during a database restart permanently disables the warm-up, and
        // nothing would say so — the next symptom would be unscreened payments, hours later.
        assertThatCode(warmUp::keepWarm).doesNotThrowAnyException();
        assertThatCode(() -> warmUp.run(new DefaultApplicationArguments())).doesNotThrowAnyException();
    }
}
