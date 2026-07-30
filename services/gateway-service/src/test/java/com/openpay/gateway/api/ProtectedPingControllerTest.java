package com.openpay.gateway.api;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpay.security.ApiKeyPrincipal;
import com.openpay.security.AuthServiceClient;
import com.openpay.security.AuthServiceUnavailableException;
import com.openpay.security.InvalidApiKeyException;
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
    void rejectsInvalidApiKey() throws Exception {
        when(authServiceClient.validateApiKey(eq("bad-key")))
                .thenThrow(new InvalidApiKeyException("API key is invalid"));

        mockMvc.perform(get("/api/v1/protected/ping").header("X-Api-Key", "bad-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_api_key"));
    }

    @Test
    void reportsAuthOutageAsUnavailableRatherThanUnauthorized() throws Exception {
        when(authServiceClient.validateApiKey(eq("opk_test.secret")))
                .thenThrow(new AuthServiceUnavailableException("down", new RuntimeException()));

        mockMvc.perform(get("/api/v1/protected/ping").header("X-Api-Key", "opk_test.secret"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("auth_unavailable"));
    }

    @Test
    void allowsValidApiKeyAndReturnsMerchantContext() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(authServiceClient.validateApiKey(eq("opk_test.secret")))
                .thenReturn(new ApiKeyPrincipal(merchantId, "payments:write"));

        mockMvc.perform(get("/api/v1/protected/ping").header("X-Api-Key", "opk_test.secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("authenticated"))
                .andExpect(jsonPath("$.merchantId").value(merchantId.toString()));
    }
}
