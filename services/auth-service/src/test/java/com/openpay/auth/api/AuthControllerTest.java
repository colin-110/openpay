package com.openpay.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.auth.application.ApiKeyService;
import com.openpay.auth.application.UserService;
import com.openpay.auth.application.InvalidApiKeyException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import(ApiExceptionHandler.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private UserService userService;

    @Test
    void createsApiKey() throws Exception {
        UUID apiKeyId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        when(apiKeyService.createApiKey(any(CreateApiKeyRequest.class))).thenReturn(new CreateApiKeyResponse(
                apiKeyId,
                merchantId,
                "primary",
                "payments:write",
                "opk_abcd1234",
                "opk_abcd1234.secret-value",
                "ACTIVE",
                OffsetDateTime.now().plusDays(30),
                OffsetDateTime.now()));

        mockMvc.perform(post("/api/v1/api-keys")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new CreateApiKeyRequest(merchantId, "primary", "payments:write", OffsetDateTime.now().plusDays(30)))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/api-keys/" + apiKeyId))
                .andExpect(jsonPath("$.apiKey").value("opk_abcd1234.secret-value"));
    }

    @Test
    void validatesApiKey() throws Exception {
        UUID merchantId = UUID.randomUUID();
        when(apiKeyService.validateKey(eq("opk_abcd1234.secret-value")))
                .thenReturn(new ValidateApiKeyResponse(true, merchantId, "payments:write", "ACTIVE"));

        mockMvc.perform(post("/api/v1/auth/validate-key")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ValidateApiKeyRequest("opk_abcd1234.secret-value"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.scope").value("payments:write"));
    }

    @Test
    void returnsUnauthorizedForInvalidApiKey() throws Exception {
        when(apiKeyService.validateKey(eq("bad-key"))).thenThrow(new InvalidApiKeyException("API key is invalid"));

        mockMvc.perform(post("/api/v1/auth/validate-key")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new ValidateApiKeyRequest("bad-key"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_api_key"));
    }
}
