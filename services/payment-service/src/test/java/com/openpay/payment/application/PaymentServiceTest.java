package com.openpay.payment.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.events.OpenPayTopics;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.infrastructure.FraudScreeningClient;
import com.openpay.payment.api.PaymentMethodRequest;
import com.openpay.payment.infrastructure.TokenNotRedeemableException;
import com.openpay.payment.infrastructure.VaultClient;
import com.openpay.payment.infrastructure.FraudScreeningClient.ScreeningOutcome;
import com.openpay.outbox.OutboxWriter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    private static final String IDEMPOTENCY_KEY = "key-123";
    private static final long USD_100 = 10_000L;

    @Mock
    private PaymentRepository paymentRepository;

    @Mock
    private PaymentEventRepository paymentEventRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private FraudScreeningClient fraudScreeningClient;

    @Mock
    private VaultClient vaultClient;

    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        // Runs the callback inline on the calling thread. These are unit tests with mocked
        // repositories, so there is no real transaction to demarcate — what matters is that the
        // code inside the template still executes, and that setRollbackOnly() on the
        // concurrent-duplicate path is observable rather than throwing.
        // lenient: the tests that refuse a payment before it is ever written — a BLOCKED screening,
        // an idempotent replay — never reach the transaction at all, and strict stubbing would
        // rightly call this unused there.
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(new SimpleTransactionStatus()));

        paymentService = new PaymentService(
                paymentRepository, paymentEventRepository, outboxWriter, objectMapper, fraudScreeningClient,
                new PaymentMetrics(new SimpleMeterRegistry()), transactionTemplate, vaultClient, false);
    }

    @Test
    void createsPaymentAndRecordsEvent() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.ALLOWED);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isTrue();
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.CREATED);
        assertThat(result.payment().amount()).isEqualTo(USD_100);

        ArgumentCaptor<PaymentEvent> eventCaptor = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(paymentEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getType()).isEqualTo("PAYMENT_CREATED");
        assertThat(eventCaptor.getValue().getPaymentId()).isEqualTo(result.payment().id());
    }

    @Test
    void aTokenDecidesThePaymentMethodAndTheCallerSDescriptionIsIgnored() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.ALLOWED);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);
        when(vaultClient.redeem("tok_real", merchantId)).thenReturn(
                new VaultClient.RedeemedInstrument("card", "amex", "0005", null, null, merchantId));

        // The caller claims visa/4242 alongside a token that was actually an Amex. The token wins,
        // outright — a payment method that is partly authoritative and partly claimed is one
        // nobody can reason about, so the claimed fields are not merged, they are discarded.
        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY,
                requestWithMethod(new PaymentMethodRequest("card", "visa", "4242", null, null, "tok_real")));

        assertThat(result.payment().paymentMethod().network()).isEqualTo("amex");
        assertThat(result.payment().paymentMethod().last4()).isEqualTo("0005");
    }

    @Test
    void aTokenThatCannotBeSpentRefusesThePaymentAndWritesNothing() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(vaultClient.redeem("tok_spent", merchantId))
                .thenThrow(new TokenNotRedeemableException("That payment token is invalid, expired, or already used"));

        assertThatThrownBy(() -> paymentService.createPayment(merchantId, IDEMPOTENCY_KEY,
                requestWithMethod(new PaymentMethodRequest(null, null, null, null, null, "tok_spent"))))
                .isInstanceOf(TokenNotRedeemableException.class);

        // Nothing at all: no payment row, no outbox event, and — the one that matters for the
        // customer — no screening decision and no burnt idempotency key, so a genuine retry with a
        // fresh token still works rather than replaying a failure.
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(outboxWriter, fraudScreeningClient);
    }

    @Test
    void aPaymentWithNoTokenNeverCallsTheVault() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.ALLOWED);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        // Integrations that never tokenise keep working exactly as before. There is no card number
        // behind them to be authoritative about, so their own description is the only information
        // that exists — and this is what keeps the acceptance suite and every existing merchant
        // running unchanged.
        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY,
                requestWithMethod(new PaymentMethodRequest("upi", null, null, "someone@okhdfcbank", null, null)));

        assertThat(result.payment().paymentMethod().vpa()).isEqualTo("so***@okhdfcbank");
        verifyNoInteractions(vaultClient);
    }

    @Test
    void aBlockedPaymentIsNeverPersisted() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(FraudStatus.BLOCKED, "extreme-value-payment", "too big"));

        assertThatThrownBy(() ->
                paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD")))
                .isInstanceOf(PaymentBlockedException.class);

        // A refused payment is not a payment that happened. Storing a FAILED row would put traffic
        // the merchant never took into their list.
        verify(paymentRepository, never()).saveAndFlush(any(Payment.class));
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void aHeldPaymentIsStoredButNotAnnouncedForRouting() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.HELD);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.payment().fraudStatus()).isEqualTo(FraudStatus.HELD);
        // Publishing PAYMENT_CREATED is what starts routing. A held payment must not reach an
        // acquirer, so the event is withheld until a review releases it.
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void aPaymentThatCouldNotBeScreenedSaysSo() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.UNSCREENED);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        // It goes through — but "we decided this was fine" and "nobody looked" must not read the
        // same afterwards.
        assertThat(result.payment().fraudStatus()).isEqualTo(FraudStatus.UNSCREENED);
        verify(outboxWriter).append(any(), eq(OpenPayTopics.PAYMENT_CREATED), any(), any());
    }

    @Test
    void releasingAHeldPaymentAnnouncesTheRoutingItWithheld() {
        Payment held = heldPayment();
        when(paymentRepository.findById(held.getId())).thenReturn(Optional.of(held));

        assertThat(paymentService.applyScreeningOutcome(held.getId(), true, null)).isTrue();

        assertThat(held.getFraudStatus()).isEqualTo(FraudStatus.ALLOWED);
        assertThat(held.getStatus()).isEqualTo(PaymentStatus.CREATED);
        verify(outboxWriter).append(any(), eq(OpenPayTopics.PAYMENT_CREATED), any(), any());
    }

    @Test
    void blockingAHeldPaymentFailsIt() {
        Payment held = heldPayment();
        when(paymentRepository.findById(held.getId())).thenReturn(Optional.of(held));

        assertThat(paymentService.applyScreeningOutcome(held.getId(), false, "manual block")).isTrue();

        assertThat(held.getFraudStatus()).isEqualTo(FraudStatus.BLOCKED);
        assertThat(held.getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(outboxWriter).append(any(), eq(OpenPayTopics.PAYMENT_STATUS_UPDATED), any(), any());
    }

    @Test
    void aScreeningOutcomeForAPaymentThatWasNeverHeldChangesNothing() {
        Payment allowed = new Payment(
                UUID.randomUUID(), UUID.randomUUID(), IDEMPOTENCY_KEY, null, USD_100, "USD",
                null, FraudStatus.ALLOWED);
        when(paymentRepository.findById(allowed.getId())).thenReturn(Optional.of(allowed));

        // fraud.check-completed.v1 carries every decision, including the ones creation already
        // acted on. Re-publishing PAYMENT_CREATED for those would route each payment twice.
        assertThat(paymentService.applyScreeningOutcome(allowed.getId(), true, null)).isFalse();
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void replayWithSameBodyReturnsOriginalAndReportsNotCreated() {
        UUID merchantId = UUID.randomUUID();
        Payment existing = existingPayment(merchantId, USD_100, "USD");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existing));

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isFalse();
        assertThat(result.payment().id()).isEqualTo(existing.getId());
    }

    @Test
    void replayWithDifferentAmountIsRejected() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existingPayment(merchantId, USD_100, "USD")));

        assertThatThrownBy(() ->
                paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(99_999_900L, "USD")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void replayWithDifferentCurrencyIsRejected() {
        UUID merchantId = UUID.randomUUID();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(existingPayment(merchantId, USD_100, "USD")));

        assertThatThrownBy(() ->
                paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "EUR")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void concurrentDuplicateReturnsTheWinningPayment() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.ALLOWED);
        Payment winner = existingPayment(merchantId, USD_100, "USD");
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(paymentRepository.saveAndFlush(any(Payment.class)))
                .thenThrow(new DataIntegrityViolationException("unique violation"));

        PaymentResult result = paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isFalse();
        assertThat(result.payment().id()).isEqualTo(winner.getId());
    }

    @Test
    void getPaymentIsScopedToTheOwningMerchant() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        when(paymentRepository.findByIdAndMerchantId(paymentId, merchantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.getPayment(merchantId, paymentId))
                .isInstanceOf(PaymentNotFoundException.class);
    }

    // ---------------------------------------------------------------------------------------
    // Asynchronous screening (openpay.fraud.async=true)
    //
    // A second mode with a genuinely different contract: the merchant is told 201 before the risk
    // rules have run. The safety property that has to survive is the one that keeps money safe —
    // nothing reaches an acquirer until screening allows it — and it is worth testing precisely
    // because the mode makes the payment durable *first*, which is exactly the change that could
    // break it.
    // ---------------------------------------------------------------------------------------

    @Test
    void asynchronousModeAcceptsWithoutCallingFraudServiceInTheRequest() {
        UUID merchantId = UUID.randomUUID();
        PaymentService async = asyncPaymentService();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        PaymentResult result = async.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        assertThat(result.created()).isTrue();
        assertThat(result.payment().fraudStatus()).isEqualTo(FraudStatus.HELD);
        // The entire point of the mode: the network round trip to fraud-service is no longer
        // inside the merchant's request.
        verifyNoInteractions(fraudScreeningClient);
    }

    @Test
    void asynchronousModeDoesNotAnnounceRoutingUntilScreeningAllows() {
        UUID merchantId = UUID.randomUUID();
        PaymentService async = asyncPaymentService();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        async.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        // The safety property, stated as a test. If PAYMENT_CREATED were published here, an
        // unscreened payment would be routed to a real acquirer — which is the one outcome this
        // mode is not allowed to produce, however fast it makes the response.
        verify(outboxWriter, never()).append(
                any(), eq(OpenPayTopics.PAYMENT_CREATED), any(), any());
        verify(outboxWriter).append(
                any(), eq(OpenPayTopics.FRAUD_CHECK_REQUESTED), any(), any());
    }

    @Test
    void asynchronousModeMarksThePaymentAsWaitingOnAMachineNotAHuman() {
        UUID merchantId = UUID.randomUUID();
        PaymentService async = asyncPaymentService();
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        async.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        verify(paymentRepository).saveAndFlush(saved.capture());
        // Without this marker a payment waiting on a dead fraud-service is indistinguishable from
        // one waiting on an operator, and StuckScreeningMonitor cannot tell an incident from a
        // Tuesday.
        assertThat(saved.getValue().getScreeningRequestedAt()).isNotNull();
    }

    @Test
    void synchronousModeLeavesNoAsynchronousMarkerOnARuleHold() {
        UUID merchantId = UUID.randomUUID();
        screeningReturns(FraudStatus.HELD);
        when(paymentRepository.findByMerchantIdAndIdempotencyKey(merchantId, IDEMPOTENCY_KEY))
                .thenReturn(Optional.empty());
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArguments()[0]);

        paymentService.createPayment(merchantId, IDEMPOTENCY_KEY, request(USD_100, "USD"));

        verify(paymentRepository).saveAndFlush(saved.capture());
        // A rule hold waits for a person and may legitimately sit for hours. Marking it would make
        // the stuck-screening gauge alert on entirely normal review-queue traffic.
        assertThat(saved.getValue().getScreeningRequestedAt()).isNull();
    }

    @Test
    void resolvingAReviewClearsTheWaitingMarker() {
        Payment held = heldPayment();
        held.awaitAsynchronousScreening();
        assertThat(held.getScreeningRequestedAt()).isNotNull();

        held.resolveScreening(FraudStatus.ALLOWED);

        // Left behind, this would report a perfectly resolved payment as permanently stuck.
        assertThat(held.getScreeningRequestedAt()).isNull();
    }

    private PaymentService asyncPaymentService() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.getArgument(0, TransactionCallback.class)
                        .doInTransaction(new SimpleTransactionStatus()));
        return new PaymentService(
                paymentRepository, paymentEventRepository, outboxWriter, objectMapper, fraudScreeningClient,
                new PaymentMetrics(new SimpleMeterRegistry()), transactionTemplate, vaultClient, true);
    }

    private CreatePaymentRequest request(long minorUnits, String currency) {
        return new CreatePaymentRequest(minorUnits, currency, null);
    }

    private CreatePaymentRequest requestWithMethod(PaymentMethodRequest method) {
        return new CreatePaymentRequest(USD_100, "USD", method);
    }

    private void screeningReturns(FraudStatus status) {
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(status, null, null));
    }

    private Payment heldPayment() {
        return new Payment(
                UUID.randomUUID(), UUID.randomUUID(), IDEMPOTENCY_KEY, null, USD_100, "USD",
                null, FraudStatus.HELD);
    }

    /** Builds a stored payment carrying the fingerprint the service would have written. */
    private Payment existingPayment(UUID merchantId, long minorUnits, String currency) {
        Payment payment = new Payment(
                UUID.randomUUID(), merchantId, IDEMPOTENCY_KEY, null, minorUnits, currency,
                null, FraudStatus.ALLOWED);
        try {
            var method = PaymentService.class.getDeclaredMethod("fingerprint", CreatePaymentRequest.class);
            method.setAccessible(true);
            String fingerprint = (String) method.invoke(paymentService, request(minorUnits, currency));
            var field = Payment.class.getDeclaredField("requestFingerprint");
            field.setAccessible(true);
            field.set(payment, fingerprint);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        return payment;
    }
}
