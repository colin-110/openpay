package com.openpay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.openpay.events.OpenPayTopics;
import com.openpay.outbox.OutboxWriter;
import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

/**
 * The backstop for the one failure asynchronous screening introduces that has no natural floor: a
 * payment committed as HELD whose screening answer never arrives.
 */
@ExtendWith(MockitoExtension.class)
class StuckScreeningMonitorTest {

    private static final Duration DEADLINE = Duration.ofMinutes(2);

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private OutboxWriter outboxWriter;

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @Test
    void doesNothingAndPublishesZeroWhenNothingIsWaiting() {
        when(paymentRepository.countByScreeningRequestedAtBefore(any())).thenReturn(0L);
        StuckScreeningMonitor monitor = monitor();

        monitor.reDriveStalledScreening();

        // The healthy case has to be genuinely free: this runs every thirty seconds forever, and
        // a sweep that read rows on every tick would be a permanent cost for an absent problem.
        verify(paymentRepository, never())
                .findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(any(), any());
        verifyNoInteractions(outboxWriter);
        assertThat(gauge()).isZero();
    }

    @Test
    void reRequestsScreeningForAPaymentThatNeverGotAnAnswer() {
        Payment stalled = awaitingSince(OffsetDateTime.now().minusMinutes(10));
        when(paymentRepository.countByScreeningRequestedAtBefore(any())).thenReturn(1L);
        when(paymentRepository.findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(
                any(), any(Pageable.class))).thenReturn(List.of(stalled));

        monitor().reDriveStalledScreening();

        // Re-asking is the recovery. FraudService.screen is idempotent on payment id, so this
        // either re-publishes the decision that was already made or makes it for the first time —
        // it cannot produce a second, different verdict.
        verify(outboxWriter).append(
                any(), eq(OpenPayTopics.FRAUD_CHECK_REQUESTED), eq(stalled.getId()), any());
    }

    @Test
    void restartsTheClockSoASweepIsARetryRatherThanALoop() {
        OffsetDateTime longAgo = OffsetDateTime.now().minusMinutes(10);
        Payment stalled = awaitingSince(longAgo);
        when(paymentRepository.countByScreeningRequestedAtBefore(any())).thenReturn(1L);
        when(paymentRepository.findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(
                any(), any(Pageable.class))).thenReturn(List.of(stalled));

        monitor().reDriveStalledScreening();

        // Without this the same payment would be past the deadline on the very next tick, and the
        // sweep would republish the same event every thirty seconds for as long as the outage
        // lasted — turning a recovery mechanism into an outbox flood.
        assertThat(stalled.getScreeningRequestedAt()).isAfter(longAgo);
    }

    @Test
    void reportsTheTrueBacklogRatherThanTheSizeOfOneBatch() {
        when(paymentRepository.countByScreeningRequestedAtBefore(any())).thenReturn(4_000L);
        when(paymentRepository.findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(
                any(), any(Pageable.class))).thenReturn(List.of(awaitingSince(OffsetDateTime.now().minusHours(1))));

        monitor().reDriveStalledScreening();

        // A gauge pinned to the batch size would read "100 stuck" whether the real number was 100
        // or 100,000, which is exactly the reading an operator would act on wrongly.
        assertThat(gauge()).isEqualTo(4_000.0);
        verify(outboxWriter, times(1)).append(any(), any(), any(), any());
    }

    @Test
    void neverFailsAPaymentOnItsOwn() {
        Payment stalled = awaitingSince(OffsetDateTime.now().minusDays(1));
        when(paymentRepository.countByScreeningRequestedAtBefore(any())).thenReturn(1L);
        when(paymentRepository.findByScreeningRequestedAtBeforeOrderByScreeningRequestedAtAsc(
                any(), any(Pageable.class))).thenReturn(List.of(stalled));

        monitor().reDriveStalledScreening();

        // Deliberate, and the most important assertion here. A payment stuck because a broker was
        // down is probably a perfectly good payment; auto-refusing it after a timeout would turn
        // an infrastructure problem into lost merchant revenue. It stays held and visible, and a
        // human decides.
        assertThat(stalled.getFraudStatus()).isEqualTo(FraudStatus.HELD);
        assertThat(stalled.getStatus().name()).isEqualTo("CREATED");
    }

    private StuckScreeningMonitor monitor() {
        return new StuckScreeningMonitor(paymentRepository, outboxWriter, meterRegistry, DEADLINE, 100);
    }

    private double gauge() {
        return meterRegistry.get("openpay.payments.awaiting.screening").gauge().value();
    }

    private Payment awaitingSince(OffsetDateTime when) {
        Payment payment = new Payment(
                UUID.randomUUID(), UUID.randomUUID(), "key-" + UUID.randomUUID(), null,
                10_000L, "INR", null, FraudStatus.HELD);
        payment.awaitAsynchronousScreening();
        setScreeningRequestedAt(payment, when);
        return payment;
    }

    /** The field is set to "now" by the domain method, so an aged row has to be built directly. */
    private void setScreeningRequestedAt(Payment payment, OffsetDateTime when) {
        try {
            var field = Payment.class.getDeclaredField("screeningRequestedAt");
            field.setAccessible(true);
            field.set(payment, when);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
