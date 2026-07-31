package com.openpay.payment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class PaymentStateMachineTest {

    @Test
    void newPaymentStartsInCreated() {
        assertThat(payment().getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,PENDING_PROVIDER",
            "CREATED,FAILED",
            "CREATED,CANCELLED",
            "PENDING_PROVIDER,AUTHORIZED",
            "PENDING_PROVIDER,CAPTURED",
            "PENDING_PROVIDER,FAILED",
            "AUTHORIZED,CAPTURED",
            "AUTHORIZED,FAILED",
            "AUTHORIZED,CANCELLED",
            "CAPTURED,REFUNDED",
            // Callbacks and routing notifications are on separate topics with no ordering between
            // them, so an outcome can legitimately arrive before the routing notification.
            "CREATED,AUTHORIZED",
            "CREATED,CAPTURED",
    })
    void allowsLegalTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
    }

    @ParameterizedTest
    @CsvSource({
            "CREATED,CREATED",
            "PENDING_PROVIDER,CREATED",
            "PENDING_PROVIDER,CANCELLED",
            "AUTHORIZED,PENDING_PROVIDER",
            "CAPTURED,FAILED",
            "CAPTURED,AUTHORIZED",
            "FAILED,AUTHORIZED",
            "CANCELLED,CAPTURED",
            "CREATED,REFUNDED",
            "AUTHORIZED,REFUNDED",
            "REFUNDED,CAPTURED",
    })
    void rejectsIllegalTransitions(PaymentStatus from, PaymentStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"FAILED", "CANCELLED", "REFUNDED"})
    void terminalStatesAcceptNothing(PaymentStatus terminal) {
        assertThat(terminal.isTerminal()).isTrue();
        assertThat(terminal.allowedTransitions()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"CREATED", "PENDING_PROVIDER", "AUTHORIZED", "CAPTURED"})
    void nonTerminalStatesCanStillMove(PaymentStatus status) {
        assertThat(status.isTerminal()).isFalse();
        assertThat(status.allowedTransitions()).isNotEmpty();
    }

    @Test
    void entityRefusesAnIllegalTransition() {
        Payment payment = payment();

        // A payment cannot be refunded before any money has been taken.
        assertThatThrownBy(() -> payment.transitionTo(PaymentStatus.REFUNDED))
                .isInstanceOf(InvalidPaymentTransitionException.class);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CREATED);
    }

    @Test
    void entityAdvancesThroughTheHappyPathAndStampsUpdatedAt() throws InterruptedException {
        Payment payment = payment();
        var createdUpdatedAt = payment.getUpdatedAt();
        Thread.sleep(2);

        payment.transitionTo(PaymentStatus.PENDING_PROVIDER);
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
                UUID.randomUUID(), UUID.randomUUID(), "key-1", "fingerprint", 1_000L, "USD");
    }
}
