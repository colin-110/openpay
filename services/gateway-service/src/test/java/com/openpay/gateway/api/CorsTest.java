package com.openpay.gateway.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpay.security.AuthServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

/** The dashboard is served from a different origin, so the gateway has to answer for it. */
@SpringBootTest(properties = "openpay.security.allowed-origins=http://localhost:5173")
@AutoConfigureMockMvc
class CorsTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthServiceClient authServiceClient;

    @Test
    void answersPreflightWithoutDemandingACredential() throws Exception {
        // A preflight carries no API key and no session by definition. If authentication ran first
        // the browser would see a 401 and never send the real request.
        mockMvc.perform(options("/api/v1/payments")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void refusesPreflightFromAnUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/payments")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void stillAuthenticatesTheRealRequest() throws Exception {
        // Allowing the preflight through must not turn into allowing the request through.
        mockMvc.perform(get("/api/v1/payments").header("Origin", "http://localhost:5173"))
                .andExpect(status().isUnauthorized());
    }
}
