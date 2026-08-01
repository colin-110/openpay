package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.events.OpenPayTopics;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.PaymentMethodRequest;
import com.openpay.payment.api.PaymentMethodView;
import com.openpay.payment.api.PaymentResponse;
import com.openpay.payment.application.IdempotencyKeyConflictException;
import com.openpay.payment.application.PaymentResult;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.FraudStatus;
import com.openpay.payment.domain.Payment;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.payment.infrastructure.FraudScreeningClient;
import com.openpay.payment.infrastructure.FraudScreeningClient.ScreeningOutcome;
import com.openpay.outbox.OutboxEvent;
import com.openpay.outbox.OutboxRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Exercises the write path against a real PostgreSQL.
 *
 * <p>This is the test the suite was missing. Every previous test either mocked the repository or
 * disabled the DataSource entirely, so a jsonb column the entity bound as varchar — which made
 * {@code POST /api/v1/payments} fail with a 500 for every single request — still looked green.
 * Booting the real schema also makes Hibernate's {@code ddl-auto: validate} a genuine check that
 * entities match the Flyway migrations.
 */
// No broker in this test: the outbox row is asserted directly, and the relay and
// listeners are switched off so they do not poll a Kafka that is not running.
@SpringBootTest(properties = {
        "openpay.outbox.relay-enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
@Testcontainers
class PaymentPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    /**
     * Stubbed rather than left to fail open against a fraud-service that is not running. Relying on
     * a connection being refused would make these tests assert the fallback instead of the write
     * path they exist to cover.
     */
    @MockitoBean
    private FraudScreeningClient fraudScreeningClient;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @BeforeEach
    void screeningAllowsEverythingUnlessATestSaysOtherwise() {
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(FraudStatus.ALLOWED, null, null));
    }

    @Test
    void aHeldPaymentSurvivesARoundTripAndIsReleasedByAReview() {
        UUID merchantId = UUID.randomUUID();
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(FraudStatus.HELD, "high-value-payment", "too big"));

        UUID paymentId = paymentService.createPayment(
                merchantId, "it-held-1", new CreatePaymentRequest(90_000_00L, "INR", null)).payment().id();

        Payment stored = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(stored.getFraudStatus()).isEqualTo(FraudStatus.HELD);
        assertThat(routingEventsFor(paymentId)).isEmpty();

        assertThat(paymentService.applyScreeningOutcome(paymentId, true, null)).isTrue();

        // The event creation withheld is published now, and only now.
        assertThat(paymentRepository.findById(paymentId).orElseThrow().getFraudStatus())
                .isEqualTo(FraudStatus.ALLOWED);
        assertThat(routingEventsFor(paymentId)).hasSize(1);
    }

    @Test
    void aReviewedBlockFailsThePaymentWithoutEverRoutingIt() {
        UUID merchantId = UUID.randomUUID();
        when(fraudScreeningClient.screen(any(), any(), anyLong(), any(), any()))
                .thenReturn(new ScreeningOutcome(FraudStatus.HELD, "high-value-payment", "too big"));

        UUID paymentId = paymentService.createPayment(
                merchantId, "it-held-2", new CreatePaymentRequest(90_000_00L, "INR", null)).payment().id();

        paymentService.applyScreeningOutcome(paymentId, false, "operator said no");

        Payment stored = paymentRepository.findById(paymentId).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(stored.getFraudStatus()).isEqualTo(FraudStatus.BLOCKED);
        assertThat(routingEventsFor(paymentId)).isEmpty();
    }

    private List<OutboxEvent> routingEventsFor(UUID paymentId) {
        return outboxRepository.findAll().stream()
                .filter(event -> event.getTopic().equals(OpenPayTopics.PAYMENT_CREATED))
                .filter(event -> event.getAggregateId().equals(paymentId.toString()))
                .toList();
    }

    @Test
    void persistsAPaymentAndItsJsonbEvent() throws Exception {
        UUID merchantId = UUID.randomUUID();

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-key-1", new CreatePaymentRequest(10_000L, "USD", null));

        assertThat(result.created()).isTrue();
        assertThat(paymentRepository.findById(result.payment().id())).isPresent();

        PaymentEvent event = paymentEventRepository.findAll().stream()
                .filter(candidate -> candidate.getPaymentId().equals(result.payment().id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no event recorded for the new payment"));

        assertThat(event.getType()).isEqualTo("PAYMENT_CREATED");
        // Parse rather than substring-match: Postgres re-serialises jsonb with its own spacing
        // and key order, so the bytes that come back are not the bytes that went in.
        JsonNode payload = new ObjectMapper().readTree(event.getPayload());
        assertThat(payload.get("status").asText()).isEqualTo("CREATED");
        assertThat(payload.get("merchantId").asText()).isEqualTo(merchantId.toString());
        assertThat(payload.get("currency").asText()).isEqualTo("USD");
    }

    @Test
    void roundTripsLargeAmountsExactly() {
        UUID merchantId = UUID.randomUUID();
        // Well beyond a 32-bit int, to prove the column and the mapping are both 64-bit.
        long largeAmount = 99_999_999_999_999L;

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-key-large", new CreatePaymentRequest(largeAmount, "USD", null));

        assertThat(paymentRepository.findById(result.payment().id()).orElseThrow().getAmount())
                .isEqualTo(largeAmount);
    }

    @Test
    void preservesTheInstantAcrossTimeZones() {
        UUID merchantId = UUID.randomUUID();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-key-tz", new CreatePaymentRequest(500L, "EUR", null));

        // Would drift if the column were `timestamp without time zone`, as V2 originally created it.
        OffsetDateTime stored = paymentRepository.findById(result.payment().id()).orElseThrow().getCreatedAt();
        assertThat(stored).isAfter(before);
        assertThat(stored).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    }

    @Test
    void replayingAKeyWithTheSameBodyReturnsTheOriginal() {
        UUID merchantId = UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(4_200L, "USD", null);

        PaymentResult first = paymentService.createPayment(merchantId, "it-key-2", request);
        PaymentResult second = paymentService.createPayment(merchantId, "it-key-2", request);

        assertThat(second.created()).isFalse();
        assertThat(second.payment().id()).isEqualTo(first.payment().id());
        assertThat(paymentRepository.findByMerchantIdOrderByCreatedAtDesc(merchantId, PageRequest.of(0, 10))
                .getTotalElements()).isEqualTo(1);
    }

    @Test
    void replayingAKeyWithADifferentBodyIsRejected() {
        UUID merchantId = UUID.randomUUID();
        paymentService.createPayment(
                merchantId, "it-key-3", new CreatePaymentRequest(1_000L, "USD", null));

        assertThatThrownBy(() -> paymentService.createPayment(
                merchantId, "it-key-3", new CreatePaymentRequest(999_900L, "USD", null)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void oneMerchantCannotReadAnothersPayment() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        PaymentResult result = paymentService.createPayment(
                owner, "it-key-4", new CreatePaymentRequest(700L, "USD", null));

        assertThatThrownBy(() -> paymentService.getPayment(stranger, result.payment().id()))
                .isInstanceOf(RuntimeException.class);
        assertThat(paymentService.getPayment(owner, result.payment().id()).id())
                .isEqualTo(result.payment().id());
    }

    @Test
    void walksTheProviderDrivenLifecycle() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = paymentService.createPayment(
                merchantId, "it-key-5", new CreatePaymentRequest(1_500L, "USD", null)).payment().id();

        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.PENDING_PROVIDER, "routed")).isTrue();
        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.AUTHORIZED, "callback")).isTrue();
        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.CAPTURED, "callback")).isTrue();

        assertThat(paymentService.getPayment(merchantId, paymentId).status()).isEqualTo(PaymentStatus.CAPTURED);
        assertThat(paymentEventRepository.findAll())
                .filteredOn(event -> event.getPaymentId().equals(paymentId))
                .extracting(PaymentEvent::getType)
                .contains("PAYMENT_CREATED", "PAYMENT_PENDING_PROVIDER", "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED");
    }

    @Test
    void anOutcomeArrivingBeforeTheRoutingNotificationIsStillApplied() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = paymentService.createPayment(
                merchantId, "it-key-order", new CreatePaymentRequest(1_500L, "USD", null)).payment().id();

        // The two events are on separate topics, so this order is entirely possible. Refusing the
        // outcome here would strand the payment once the routing notification arrived.
        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.CAPTURED, "callback first"))
                .isTrue();
        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.PENDING_PROVIDER, "late routing"))
                .isFalse();

        assertThat(paymentService.getPayment(merchantId, paymentId).status())
                .isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void redeliveredCallbacksAreAbsorbedRatherThanFailing() {
        UUID merchantId = UUID.randomUUID();
        UUID paymentId = paymentService.createPayment(
                merchantId, "it-key-dup", new CreatePaymentRequest(2_500L, "USD", null)).payment().id();
        paymentService.applyTransition(paymentId, PaymentStatus.PENDING_PROVIDER, "routed");
        paymentService.applyTransition(paymentId, PaymentStatus.CAPTURED, "callback");

        // Acquirers re-send callbacks and Kafka delivers at least once. A repeat of an outcome we
        // already applied must be a quiet no-op, not an exception that puts the consumer in a
        // redelivery loop.
        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.CAPTURED, "redelivery")).isFalse();
        assertThat(paymentService.applyTransition(paymentId, PaymentStatus.AUTHORIZED, "late redelivery")).isFalse();
        assertThat(paymentService.getPayment(merchantId, paymentId).status()).isEqualTo(PaymentStatus.CAPTURED);
    }

    @Test
    void everyPaymentWritesAnOutboxRowInTheSameTransaction() {
        UUID merchantId = UUID.randomUUID();
        long before = outboxRepository.count();

        UUID paymentId = paymentService.createPayment(
                merchantId, "it-key-outbox", new CreatePaymentRequest(3_300L, "USD", null)).payment().id();

        assertThat(outboxRepository.count()).isEqualTo(before + 1);
        OutboxEvent event = outboxRepository.findAll().stream()
                .filter(candidate -> candidate.getAggregateId().equals(paymentId.toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no outbox row for the new payment"));

        assertThat(event.getTopic()).isEqualTo(OpenPayTopics.PAYMENT_CREATED);
        assertThat(event.getPublishedAt()).isNull();
        assertThat(event.getPayload()).contains(paymentId.toString());
    }

    @Test
    void listingIsScopedToTheMerchantAndNewestFirst() {
        UUID merchantId = UUID.randomUUID();
        paymentService.createPayment(
                merchantId, "it-list-1", new CreatePaymentRequest(100L, "USD", null));
        paymentService.createPayment(
                merchantId, "it-list-2", new CreatePaymentRequest(200L, "USD", null));
        paymentService.createPayment(
                UUID.randomUUID(), "it-list-3", new CreatePaymentRequest(300L, "USD", null));

        var page = paymentService.listPayments(merchantId, null, PageRequest.of(0, 10));

        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items()).extracting(PaymentResponse::amount)
                .allSatisfy(amount -> assertThat(amount).isLessThan(300L));
    }

    @Test
    void keepsOnlyTheSafeHalfOfAPaymentMethod() {
        UUID merchantId = UUID.randomUUID();

        PaymentResult result = paymentService.createPayment(
                merchantId,
                "it-method-upi",
                new CreatePaymentRequest(150_000L, "INR", new PaymentMethodRequest(
                        "upi", null, null, "colinthomas@okhdfcbank", "HDFC", "tok_live_do_not_store")));

        PaymentMethodView method = result.payment().paymentMethod();
        assertThat(method.type()).isEqualTo("upi");
        assertThat(method.vpa()).isEqualTo("co***@okhdfcbank");
        assertThat(method.bank()).isEqualTo("HDFC");

        // The instrument token is what the acquirer needs, not what this service keeps. Assert on
        // the whole stored row so a future column cannot quietly start holding it.
        Payment stored = paymentRepository.findById(result.payment().id()).orElseThrow();
        assertThat(stored.getPaymentMethod().getVpa()).doesNotContain("colinthomas");
        assertThat(reflectAllStringFields(stored.getPaymentMethod())).noneMatch(
                value -> value != null && value.contains("tok_live_do_not_store"));
    }

    @Test
    void aPaymentWithNoMethodStoresNoneRatherThanAnEmptyOne() {
        UUID merchantId = UUID.randomUUID();

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-method-absent", new CreatePaymentRequest(999L, "INR", null));

        assertThat(result.payment().paymentMethod()).isNull();
        assertThat(paymentRepository.findById(result.payment().id()).orElseThrow().getPaymentMethod())
                .isNull();
    }

    private java.util.List<String> reflectAllStringFields(Object target) {
        return java.util.Arrays.stream(target.getClass().getDeclaredFields())
                .peek(field -> field.setAccessible(true))
                .filter(field -> field.getType() == String.class)
                .map(field -> {
                    try {
                        return (String) field.get(target);
                    } catch (IllegalAccessException exception) {
                        throw new AssertionError(exception);
                    }
                })
                .toList();
    }

    @Test
    void listingCanBeNarrowedToOneStatus() {
        UUID merchantId = UUID.randomUUID();
        paymentService.createPayment(merchantId, "it-status-1", new CreatePaymentRequest(100L, "INR", null));
        paymentService.createPayment(merchantId, "it-status-2", new CreatePaymentRequest(200L, "INR", null));

        var created = paymentService.listPayments(merchantId, PaymentStatus.CREATED, PageRequest.of(0, 10));
        var captured = paymentService.listPayments(merchantId, PaymentStatus.CAPTURED, PageRequest.of(0, 10));

        assertThat(created.totalItems()).isEqualTo(2);
        assertThat(captured.totalItems()).isZero();
    }
}
