package com.openpay.gateway.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.openpay.security.AuthServiceClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * A service with no dashboard traffic leaves the session and CORS filters unconfigured, and must
 * still start. This needs a real servlet container: filter registrations are only processed when
 * one starts, so a MockMvc test would not notice a registration that cannot be registered.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"openpay.security.allowed-origins=", "openpay.security.jwt-secret="})
class DisabledSecurityFiltersStartupTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private AuthServiceClient authServiceClient;

    @Test
    void startsWithNeitherSessionsNorCorsConfigured() {
        ResponseEntity<String> health = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
