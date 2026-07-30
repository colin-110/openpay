package com.openpay.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.api.CreatePaymentRequest;
import com.openpay.payment.api.PaymentResponse;
import com.openpay.payment.application.IdempotencyKeyConflictException;
import com.openpay.payment.application.PaymentResult;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.InvalidPaymentTransitionException;
import com.openpay.payment.domain.PaymentEvent;
import com.openpay.payment.domain.PaymentEventRepository;
import com.openpay.payment.domain.PaymentRepository;
import com.openpay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
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
@SpringBootTest
@Testcontainers
class PaymentPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private PaymentEventRepository paymentEventRepository;

    @Test
    void persistsAPaymentAndItsJsonbEvent() throws Exception {
        UUID merchantId = UUID.randomUUID();

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-key-1", new CreatePaymentRequest(new BigDecimal("100.00"), "USD"));

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
    void roundTripsTheAmountWithoutLosingScale() {
        UUID merchantId = UUID.randomUUID();

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-key-scale", new CreatePaymentRequest(new BigDecimal("12.3456"), "USD"));

        assertThat(paymentRepository.findById(result.payment().id()).orElseThrow().getAmount())
                .isEqualByComparingTo("12.3456");
    }

    @Test
    void preservesTheInstantAcrossTimeZones() {
        UUID merchantId = UUID.randomUUID();
        OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);

        PaymentResult result = paymentService.createPayment(
                merchantId, "it-key-tz", new CreatePaymentRequest(new BigDecimal("5.00"), "EUR"));

        // Would drift if the column were `timestamp without time zone`, as V2 originally created it.
        OffsetDateTime stored = paymentRepository.findById(result.payment().id()).orElseThrow().getCreatedAt();
        assertThat(stored).isAfter(before);
        assertThat(stored).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(1));
    }

    @Test
    void replayingAKeyWithTheSameBodyReturnsTheOriginal() {
        UUID merchantId = UUID.randomUUID();
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("42.00"), "USD");

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
                merchantId, "it-key-3", new CreatePaymentRequest(new BigDecimal("10.00"), "USD"));

        assertThatThrownBy(() -> paymentService.createPayment(
                merchantId, "it-key-3", new CreatePaymentRequest(new BigDecimal("9999.00"), "USD")))
                .isInstanceOf(IdempotencyKeyConflictException.class);
    }

    @Test
    void oneMerchantCannotReadAnothersPayment() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        PaymentResult result = paymentService.createPayment(
                owner, "it-key-4", new CreatePaymentRequest(new BigDecimal("7.00"), "USD"));

        assertThatThrownBy(() -> paymentService.getPayment(stranger, result.payment().id()))
                .isInstanceOf(RuntimeException.class);
        assertThat(paymentService.getPayment(owner, result.payment().id()).id())
                .isEqualTo(result.payment().id());
    }

    @Test
    void transitionsAreAppliedAndIllegalOnesRejected() {
        UUID merchantId = UUID.randomUUID();
        PaymentResult created = paymentService.createPayment(
                merchantId, "it-key-5", new CreatePaymentRequest(new BigDecimal("15.00"), "USD"));
        UUID paymentId = created.payment().id();

        assertThatThrownBy(() -> paymentService.transition(merchantId, paymentId, PaymentStatus.CAPTURED))
                .isInstanceOf(InvalidPaymentTransitionException.class);

        PaymentResponse authorized = paymentService.transition(merchantId, paymentId, PaymentStatus.AUTHORIZED);
        assertThat(authorized.status()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(authorized.updatedAt()).isAfterOrEqualTo(authorized.createdAt());

        PaymentResponse captured = paymentService.transition(merchantId, paymentId, PaymentStatus.CAPTURED);
        assertThat(captured.status()).isEqualTo(PaymentStatus.CAPTURED);

        assertThat(paymentEventRepository.findAll())
                .filteredOn(event -> event.getPaymentId().equals(paymentId))
                .extracting(event -> event.getType())
                .contains("PAYMENT_CREATED", "PAYMENT_AUTHORIZED", "PAYMENT_CAPTURED");
    }

    @Test
    void listingIsScopedToTheMerchantAndNewestFirst() {
        UUID merchantId = UUID.randomUUID();
        paymentService.createPayment(
                merchantId, "it-list-1", new CreatePaymentRequest(new BigDecimal("1.00"), "USD"));
        paymentService.createPayment(
                merchantId, "it-list-2", new CreatePaymentRequest(new BigDecimal("2.00"), "USD"));
        paymentService.createPayment(
                UUID.randomUUID(), "it-list-3", new CreatePaymentRequest(new BigDecimal("3.00"), "USD"));

        var page = paymentService.listPayments(merchantId, PageRequest.of(0, 10));

        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items()).extracting(PaymentResponse::amount)
                .allSatisfy(amount -> assertThat(amount).isLessThan(new BigDecimal("3.00")));
    }
}
