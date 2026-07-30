package com.openpay.gateway.routing;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.openpay.security.AuthServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ProxyRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthServiceClient authServiceClient;

    @Test
    void refusesUnauthenticatedPaymentTrafficBeforeItReachesTheUpstream() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("missing_api_key"));
    }

    @Test
    void unauthenticatedPaymentReadsAreAlsoRefused() throws Exception {
        mockMvc.perform(get("/api/v1/payments/11111111-1111-1111-1111-111111111111"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void openEndpointsStayReachable() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("gateway-service"));
    }

    @Test
    void unknownPathIs404NotAFabricated500() throws Exception {
        // The catch-all @ExceptionHandler(Exception.class) must not swallow Spring's own MVC
        // exceptions, which already carry a correct 4xx status.
        mockMvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsupportedMethodOnAKnownPathIsNotA500() throws Exception {
        mockMvc.perform(delete("/api/v1/ping"))
                .andExpect(status().isMethodNotAllowed());
    }
}
