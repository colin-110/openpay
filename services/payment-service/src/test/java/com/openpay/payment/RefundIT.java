package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.CreateRefundRequest;
import com.openpay.payment.api.RefundResponse;
import com.openpay.payment.application.IdempotencyKeyConflictException;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.application.RefundNotAllowedException;
import com.openpay.payment.application.RefundResult;
import com.openpay.payment.application.RefundService;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.domain.RefundStatus;
import com.openpay.outbox.OutboxRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = {
        "openpay.outbox.relay-enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
class RefundIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private RefundService refundService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Test
    void refundsACapturedPaymentInFull() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-1");

        RefundResult result = refundService.createRefund(
                merchantId, "refund-1", new CreateRefundRequest(paymentId, 10_000L, "customer changed mind"));

        assertThat(result.created()).isTrue();
        assertThat(result.refund().amount()).isEqualTo(10_000L);
        assertThat(result.refund().status()).isEqualTo(RefundStatus.PENDING);
    }

    @Test
    void omittingTheAmountRefundsEverythingStillRefundable() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-2");
        refundService.createRefund(merchantId, "part-1", new CreateRefundRequest(paymentId, 3_000L, null));
        succeed(merchantId, paymentId);

        RefundResult rest = refundService.createRefund(
                merchantId, "rest-1", new CreateRefundRequest(paymentId, null, null));

        assertThat(rest.refund().amount()).isEqualTo(7_000L);
    }

    @Test
    void refusesToRefundMoreThanThePaymentWasWorth() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-3");

        assertThatThrownBy(() -> refundService.createRefund(
                merchantId, "over-1", new CreateRefundRequest(paymentId, 10_001L, null)))
                .isInstanceOf(RefundNotAllowedException.class);
    }

    @Test
    void partialRefundsCannotAddUpToMoreThanTheTotal() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-4");
        refundService.createRefund(merchantId, "p1", new CreateRefundRequest(paymentId, 6_000L, null));
        refundService.createRefund(merchantId, "p2", new CreateRefundRequest(paymentId, 4_000L, null));

        // The third would take the total past the payment, even though each part looks reasonable.
        assertThatThrownBy(() -> refundService.createRefund(
                merchantId, "p3", new CreateRefundRequest(paymentId, 1L, null)))
                .isInstanceOf(RefundNotAllowedException.class);
    }

    @Test
    void aPendingRefundStillCountsAgainstTheRefundableBalance() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-5");

        // Left out of the total, several concurrent requests could each pass their own check and
        // together refund more than the payment.
        refundService.createRefund(merchantId, "pend-1", new CreateRefundRequest(paymentId, 10_000L, null));

        assertThatThrownBy(() -> refundService.createRefund(
                merchantId, "pend-2", new CreateRefundRequest(paymentId, 1L, null)))
                .isInstanceOf(RefundNotAllowedException.class);
    }

    @Test
    void aFailedRefundReleasesItsAmountAgain() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-6");
        RefundResult first = refundService.createRefund(
                merchantId, "fail-1", new CreateRefundRequest(paymentId, 10_000L, null));

        refundService.applyOutcome(first.refund().id(), RefundStatus.FAILED, "acquirer declined");

        // The money never left, so the merchant must be able to try again.
        RefundResult retry = refundService.createRefund(
                merchantId, "fail-2", new CreateRefundRequest(paymentId, 10_000L, null));
        assertThat(retry.created()).isTrue();
    }

    @Test
    void refusesToRefundAPaymentThatWasNeverCaptured() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = paymentService.createPayment(
                merchantId, "r-7", new CreatePaymentRequest(10_000L, "USD", null)).payment().id();

        // Still CREATED: no money was ever taken, so there is nothing to give back.
        assertThatThrownBy(() -> refundService.createRefund(
                merchantId, "uncaptured", new CreateRefundRequest(paymentId, 1_000L, null)))
                .isInstanceOf(RefundNotAllowedException.class);
    }

    @Test
    void replayingARefundKeyReturnsTheOriginal() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-8");

        RefundResult first = refundService.createRefund(
                merchantId, "idem-1", new CreateRefundRequest(paymentId, 2_000L, null));
        RefundResult second = refundService.createRefund(
                merchantId, "idem-1", new CreateRefundRequest(paymentId, 2_000L, null));

        assertThat(second.created()).isFalse();
        assertThat(second.refund().id()).isEqualTo(first.refund().id());
    }

    @Test
    void replayingARefundKeyWithADifferentAmountIsRejected() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-9");
        refundService.createRefund(merchantId, "conf-1", new CreateRefundRequest(paymentId, 2_000L, null));

        assertThatThrownBy(() -> refundService.createRefund(
                merchantId, "conf-1", new CreateRefundRequest(paymentId, 5_000L, null)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void aFullyRefundedPaymentBecomesREFUNDED() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-10");
        RefundResult refund = refundService.createRefund(
                merchantId, "full-1", new CreateRefundRequest(paymentId, 10_000L, null));

        refundService.applyOutcome(refund.refund().id(), RefundStatus.SUCCEEDED, null);

        assertThat(paymentService.getPayment(merchantId, paymentId).status())
                .isEqualTo(PaymentStatus.REFUNDED);
    }

    @Test
    void fullyRefundingAPaymentEmitsAStatusEvent() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-evt");
        long before = outboxRepository.count();

        RefundResult refund = refundService.createRefund(
                merchantId, "evt-1", new CreateRefundRequest(paymentId, 10_000L, null));
        refundService.applyOutcome(refund.refund().id(), RefundStatus.SUCCEEDED, null);

        // refund.created, refund.succeeded, and the payment's own REFUNDED transition. Without the
        // last one the ledger, settlement, and the merchant's webhook never learn about it.
        assertThat(outboxRepository.count()).isEqualTo(before + 3);
        assertThat(outboxRepository.findAll().stream()
                .anyMatch(event -> event.getPayload().contains("REFUNDED"))).isTrue();
    }

    @Test
    void aPartiallyRefundedPaymentStaysCAPTURED() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-11");
        RefundResult refund = refundService.createRefund(
                merchantId, "part-2", new CreateRefundRequest(paymentId, 4_000L, null));

        refundService.applyOutcome(refund.refund().id(), RefundStatus.SUCCEEDED, null);

        // Some money came back, but the payment is not undone.
        assertThat(paymentService.getPayment(merchantId, paymentId).status())
                .isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void aRedeliveredOutcomeIsAbsorbed() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-12");
        RefundResult refund = refundService.createRefund(
                merchantId, "dup-1", new CreateRefundRequest(paymentId, 1_000L, null));

        assertThat(refundService.applyOutcome(refund.refund().id(), RefundStatus.SUCCEEDED, null)).isTrue();
        assertThat(refundService.applyOutcome(refund.refund().id(), RefundStatus.SUCCEEDED, null)).isFalse();
        assertThat(refundService.applyOutcome(refund.refund().id(), RefundStatus.FAILED, "late")).isFalse();
    }

    @Test
    void oneMerchantCannotRefundAnothersPayment() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID paymentId = capturedPayment(owner, 10_000, "r-13");

        assertThatThrownBy(() -> refundService.createRefund(
                stranger, "steal-1", new CreateRefundRequest(paymentId, 1_000L, null)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void listsEveryRefundAgainstAPayment() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = capturedPayment(merchantId, 10_000, "r-14");
        refundService.createRefund(merchantId, "l1", new CreateRefundRequest(paymentId, 1_000L, null));
        refundService.createRefund(merchantId, "l2", new CreateRefundRequest(paymentId, 2_000L, null));

        assertThat(refundService.refundsForPayment(merchantId, paymentId))
                .extracting(RefundResponse::amount)
                .containsExactly(1_000L, 2_000L);
    }

    /** Drives a payment to CAPTURED the way the provider flow would. */
    private UUID capturedPayment(UUID merchantId, long amount, String key) {
        UUID paymentId = paymentService.createPayment(
                merchantId, key, new CreatePaymentRequest(amount, "USD", null)).payment().id();
        paymentService.applyTransition(paymentId, PaymentStatus.PENDING_PROVIDER, "test");
        paymentService.applyTransition(paymentId, PaymentStatus.CAPTURED, "test");
        return paymentId;
    }

    private void succeed(UUID merchantId, UUID paymentId) {
        refundService.refundsForPayment(merchantId, paymentId).stream()
                .filter(refund -> refund.status() == RefundStatus.PENDING)
                .forEach(refund -> refundService.applyOutcome(refund.id(), RefundStatus.SUCCEEDED, null));
    }
}
