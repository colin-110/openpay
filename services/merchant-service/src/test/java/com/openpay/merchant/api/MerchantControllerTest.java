package com.openpay.merchant.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.merchant.application.MerchantNotFoundException;
import com.openpay.merchant.application.MerchantService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MerchantController.class)
@Import(ApiExceptionHandler.class)
class MerchantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MerchantService merchantService;

    @Test
    void createsMerchantAndReturnsLocationHeader() throws Exception {
        UUID merchantId = UUID.randomUUID();
        MerchantResponse response = new MerchantResponse(
                merchantId,
                "justpay-demo",
                "JustPay Demo",
                "ACTIVE",
                "https://merchant.test/webhook",
                "USD",
                OffsetDateTime.now(),
                OffsetDateTime.now());

        when(merchantService.createMerchant(any(CreateMerchantRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/v1/merchants")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateMerchantRequest(
                                "justpay-demo", "JustPay Demo", "https://merchant.test/webhook", "USD"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/merchants/" + merchantId))
                .andExpect(jsonPath("$.merchantCode").value("justpay-demo"));
    }

    @Test
    void returnsMerchantById() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(merchantService.getMerchant(eq(merchantId))).thenReturn(new MerchantResponse(
                merchantId,
                "justpay-demo",
                "JustPay Demo",
                "ACTIVE",
                null,
                "USD",
                OffsetDateTime.now(),
                OffsetDateTime.now()));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}", merchantId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.merchantCode").value("justpay-demo"));
    }

    @Test
    void returnsPagedMerchants() throws Exception {
        when(merchantService.listMerchants(any())).thenReturn(new PagedResponse<>(
                List.of(new MerchantResponse(
                        UUID.randomUUID(),
                        "justpay-demo",
                        "JustPay Demo",
                        "ACTIVE",
                        null,
                        "USD",
                        OffsetDateTime.now(),
                        OffsetDateTime.now())),
                0,
                20,
                1,
                1));

        mockMvc.perform(get("/api/v1/merchants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].merchantCode").value("justpay-demo"))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void returnsNotFoundForMissingMerchant() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(merchantService.getMerchant(eq(merchantId)))
                .thenThrow(new MerchantNotFoundException("Merchant not found: " + merchantId));

        mockMvc.perform(get("/api/v1/merchants/{merchantId}", merchantId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("merchant_not_found"));
    }
}
