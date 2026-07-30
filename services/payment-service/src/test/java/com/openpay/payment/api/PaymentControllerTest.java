package com.openpay.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.application.IdempotencyKeyConflictException;
import com.openpay.payment.application.PaymentNotFoundException;
import com.openpay.payment.application.PaymentResult;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(PaymentController.class)
@Import(ApiExceptionHandler.class)
// The filter chain is exercised in PaymentApiIT; here the principal is injected directly so the
// controller's own behaviour is what is under test.
@TestPropertySource(properties = "openpay.security.api-key-paths=")
class PaymentControllerTest {

    private static final UUID MERCHANT_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    @Test
    void createReturns201WithLocationForANewPayment() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.createPayment(eq(MERCHANT_ID), eq("key-123"), any(CreatePaymentRequest.class)))
                .thenReturn(new PaymentResult(response(paymentId), true));

        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(10_000L, "USD"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/payments/" + paymentId))
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void idempotentReplayReturns200NotCreated() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.createPayment(eq(MERCHANT_ID), eq("key-123"), any(CreatePaymentRequest.class)))
                .thenReturn(new PaymentResult(response(paymentId), false));

        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(10_000L, "USD"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(paymentId.toString()));
    }

    @Test
    void reusedKeyWithDifferentBodyReturns409() throws Exception {
        when(paymentService.createPayment(eq(MERCHANT_ID), eq("key-123"), any(CreatePaymentRequest.class)))
                .thenThrow(new IdempotencyKeyConflictException("key-123"));

        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(99_900L, "USD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void missingIdempotencyKeyReturnsStructuredError() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(10_000L, "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_header"));
    }

    @Test
    void rejectsNonIsoCurrency() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":1000,\"currency\":\"###\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void rejectsFractionalAmountRatherThanTruncatingIt() throws Exception {
        // Amounts are minor units, so 10.99 is not a valid input. Jackson's default behaviour is
        // to truncate it to 10, which would silently charge the wrong amount.
        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.99,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));
    }

    @Test
    void rejectsZeroAndNegativeAmounts() throws Exception {
        for (String amount : new String[] {"0", "-500"}) {
            mockMvc.perform(authenticated(post("/api/v1/payments"))
                            .header("Idempotency-Key", "key-" + amount)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":" + amount + ",\"currency\":\"USD\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_error"));
        }
    }

    @Test
    void getReturns404AsStructuredError() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.getPayment(eq(MERCHANT_ID), eq(paymentId)))
                .thenThrow(new PaymentNotFoundException(paymentId));

        mockMvc.perform(authenticated(get("/api/v1/payments/{id}", paymentId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("payment_not_found"));
    }

    @Test
    void merchantsCannotMoveTheirOwnPaymentsForward() throws Exception {
        // Lifecycle is driven by provider callbacks, not by the merchant. If this route ever comes
        // back, a merchant could mark their own payment CAPTURED without a provider involved.
        mockMvc.perform(authenticated(post("/api/v1/payments/{id}/status", UUID.randomUUID()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CAPTURED\"}"))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder builder) {
        return builder.requestAttr(
                ApiKeyAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
                new ApiKeyPrincipal(MERCHANT_ID, "payments:write"));
    }

    private PaymentResponse response(UUID paymentId) {
        return new PaymentResponse(
                paymentId,
                PaymentStatus.CREATED,
                10_000L,
                "USD",
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
