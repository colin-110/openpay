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
import com.openpay.payment.domain.InvalidPaymentTransitionException;
import com.openpay.payment.domain.PaymentStatus;
import com.openpay.security.ApiKeyAuthenticationFilter;
import com.openpay.security.ApiKeyPrincipal;
import java.math.BigDecimal;
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
                                new CreatePaymentRequest(new BigDecimal("100.00"), "USD"))))
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
                                new CreatePaymentRequest(new BigDecimal("100.00"), "USD"))))
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
                                new CreatePaymentRequest(new BigDecimal("999.00"), "USD"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("idempotency_key_reused"));
    }

    @Test
    void missingIdempotencyKeyReturnsStructuredError() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreatePaymentRequest(new BigDecimal("100.00"), "USD"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("missing_header"));
    }

    @Test
    void rejectsNonIsoCurrency() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.00,\"currency\":\"###\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
    }

    @Test
    void rejectsAmountWithMorePrecisionThanTheColumnHolds() throws Exception {
        mockMvc.perform(authenticated(post("/api/v1/payments"))
                        .header("Idempotency-Key", "key-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":10.1234567,\"currency\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_error"));
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
    void illegalStatusTransitionReturns409() throws Exception {
        UUID paymentId = UUID.randomUUID();
        when(paymentService.transition(eq(MERCHANT_ID), eq(paymentId), eq(PaymentStatus.CAPTURED)))
                .thenThrow(new InvalidPaymentTransitionException(PaymentStatus.CREATED, PaymentStatus.CAPTURED));

        mockMvc.perform(authenticated(post("/api/v1/payments/{id}/status", paymentId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CAPTURED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_state_transition"));
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
                new BigDecimal("100.00"),
                "USD",
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
