package com.openpay.gateway.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpay.gateway.application.ApiKeyValidationResult;
import com.openpay.gateway.application.AuthServiceClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProtectedPingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthServiceClient authServiceClient;

    @Test
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/v1/protected/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("missing_api_key"));
    }

    @Test
    void allowsValidApiKeyAndReturnsMerchantContext() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(authServiceClient.validateApiKey(eq("opk_test.secret")))
                .thenReturn(new ApiKeyValidationResult(true, merchantId, "payments:write", "ACTIVE"));

        mockMvc.perform(get("/api/v1/protected/ping")
                        .header("X-Api-Key", "opk_test.secret"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Merchant-Id", merchantId.toString()))
                .andExpect(jsonPath("$.status").value("authenticated"))
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()));
    }
}
