package com.openpay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PaymentStateMachineTest {

    @Test
    void newPaymentStartsInCreated() {
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,AUTHORIZED",
            "CREATED,FAILED",
            "AUTHORIZED,CAPTURED",
            "AUTHORIZED,FAILED",
    })
    void allowsLegalTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,CAPTURED",
            "CREATED,CREATED",
            "AUTHORIZED,CREATED",
            "CAPTURED,FAILED",
            "CAPTURED,AUTHORIZED",
            "FAILED,AUTHORIZED",
            "FAILED,CAPTURED",
    })
    void rejectsIllegalTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @Test
    void capturedAndFailedAreTerminal() {
        assertThat(PaymentStatus.CAPTURED.isTerminal()).isTrue();
        assertThat(PaymentStatus.FAILED.isTerminal()).isTrue();
        assertThat(PaymentStatus.CREATED.isTerminal()).isFalse();
    }

    @Test
    void entityRefusesAnIllegalTransition() {
        Payment payment = payment();

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.CAPTURED))
                .isInstanceOf(InvalidPaymentTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void entityAdvancesThroughTheHappyPathAndStampsUpdatedAt() throws InterruptedException {
        Payment payment = payment();
        var createdUpdatedAt = payment.getUpdatedAt();
        Thread.sleep(2);

        payment.transitionTo(PaymentStatus.AUTHORIZED);
        payment.transitionTo(PaymentStatus.CAPTURED);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(payment.getUpdatedAt()).isAfter(createdUpdatedAt);
    }

    @Test
    void terminalPaymentCannotMoveAgain() {
        Payment payment = payment();
        payment.transitionTo(PaymentStatus.FAILED);

        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.AUTHORIZED))
                .isInstanceOf(InvalidPaymentTransitionException.class);
    }

    private Payment payment() {
        return new Payment(
                UUID.randomUUID(), UUID.randomUUID(), "key-1", "fingerprint", new BigDecimal("10.00"), "USD");
    }
}
