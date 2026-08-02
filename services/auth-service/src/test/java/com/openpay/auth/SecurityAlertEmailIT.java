package com.openpay.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openpay.auth.api.CreateUserRequest;
import com.openpay.auth.api.LoginRequest;
import com.openpay.auth.api.LoginResponse;
import com.openpay.auth.application.InvalidRefreshTokenException;
import com.openpay.auth.application.UserService;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The security alert email fired when refresh-token reuse is detected — against a real SMTP
 * server, not a mocked {@code EmailNotifier}.
 *
 * <p>A mock would only prove {@code sendBestEffort} was called with the right arguments, which is
 * a much weaker claim than "an email actually arrived addressed to the right person". Mailpit
 * gives up a real inbox to assert against, the same reason mock-bank-service exists rather than
 * stubbing the acquirer call out of the payment flow entirely.
 */
@SpringBootTest(properties = {
        "openpay.jwt.secret=test-secret-that-is-long-enough-for-hs256",
        "openpay.jwt.refresh-ttl=P30D"
})
@Testcontainers
class SecurityAlertEmailIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Container
    static GenericContainer<?> mailpit =
            new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.22"))
                    .withExposedPorts(1025, 8025);

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", mailpit::getHost);
        registry.add("spring.mail.port", () -> mailpit.getMappedPort(1025));
    }

    @Autowired
    private UserService userService;

    @MockBean
    private MerchantServiceClient merchantServiceClient;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void merchantsExist() {
        when(merchantServiceClient.merchantExists(any(UUID.class))).thenReturn(true);
    }

    @Test
    void refreshTokenReuseSendsARealEmailToTheAccountHolder() throws Exception {
        String email = "alert-" + UUID.randomUUID() + "@openpay.test";
        UUID merchantId = UUID.randomUUID();
        userService.createUser(new CreateUserRequest(merchantId, email, "correct-horse-battery-staple", "MERCHANT_ADMIN"));
        LoginResponse login = userService.login(
                new LoginRequest(email, "correct-horse-battery-staple"), "203.0.113.40");

        userService.refresh(login.refreshToken());
        // The rotated-away token, replayed — the theft signature the alert fires on.
        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        JsonNode message = await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> findMessageTo(email), (m) -> m != null);

        assertThat(message.path("Subject").asText()).contains("Security alert");
        assertThat(message.path("To").get(0).path("Address").asText()).isEqualTo(email);
    }

    private JsonNode findMessageTo(String email) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://" + mailpit.getHost() + ":" + mailpit.getMappedPort(8025)
                        + "/api/v1/search?query=to:" + email))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode messages = objectMapper.readTree(response.body()).path("messages");
        return messages.isEmpty() ? null : messages.get(0);
    }
}
