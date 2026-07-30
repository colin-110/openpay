package com.openpay.payment.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.payment.application.PaymentService;
import com.openpay.payment.domain.PaymentStatus;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PaymentService paymentService;

    @Test
    void createPayment_Returns201AndPaymentResponse() throws Exception {
        UUID merchantId = UUID.randomUUID();
        String idempotencyKey = "key-123";
        CreatePaymentRequest request = new CreatePaymentRequest(new BigDecimal("100.00"), "USD");

        UUID paymentId = UUID.randomUUID();
        PaymentResponse response = new PaymentResponse(paymentId, PaymentStatus.CREATED, new BigDecimal("100.00"), "USD", OffsetDateTime.now());

        when(paymentService.createPayment(eq(merchantId), eq(idempotencyKey), any(CreatePaymentRequest.class)))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/payments")
                        .header("X-Merchant-Id", merchantId.toString())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(paymentId.toString()))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.amount").value(100.00))
                .andExpect(jsonPath("$.currency").value("USD"));
    }
}
