package com.openpay.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.openpay.auth.api.CreateUserRequest;
import com.openpay.auth.api.LoginRequest;
import com.openpay.auth.api.LoginResponse;
import com.openpay.auth.application.InvalidCredentialsException;
import com.openpay.auth.application.UnknownMerchantException;
import com.openpay.auth.application.UserService;
import com.openpay.auth.domain.UserRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "openpay.jwt.secret=test-secret-that-is-long-enough-for-hs256")
@Testcontainers
class UserLoginIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @MockBean
    private MerchantServiceClient merchantServiceClient;

    @BeforeEach
    void merchantsExist() {
        when(merchantServiceClient.merchantExists(any(UUID.class))).thenReturn(true);
    }

    @Test
    void logsInAndIssuesASessionScopedToTheMerchant() {
        UUID merchantId = UUID.randomUUID();
        String email = unique("owner");
        userService.createUser(new CreateUserRequest(merchantId, email, "correct-horse-battery", "MERCHANT_ADMIN"));

        LoginResponse response = userService.login(new LoginRequest(email, "correct-horse-battery"));

        assertThat(response.token()).isNotBlank();
        assertThat(response.merchantId()).isEqualTo(merchantId);
        assertThat(response.role()).isEqualTo("MERCHANT_ADMIN");
        assertThat(response.expiresAt()).isNotNull();
    }

    @Test
    void neverStoresThePasswordItself() {
        String email = unique("hash");
        userService.createUser(
                new CreateUserRequest(UUID.randomUUID(), email, "correct-horse-battery", "MERCHANT_ADMIN"));

        String stored = userRepository.findByEmail(email).orElseThrow().getPasswordHash();
        assertThat(stored).doesNotContain("correct-horse-battery").startsWith("$2");
    }

    @Test
    void rejectsAWrongPassword() {
        String email = unique("wrong");
        userService.createUser(
                new CreateUserRequest(UUID.randomUUID(), email, "correct-horse-battery", "MERCHANT_ADMIN"));

        assertThatThrownBy(() -> userService.login(new LoginRequest(email, "not-the-password")))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void anUnknownEmailFailsTheSameWayAsAWrongPassword() {
        // Identical exception and message: distinguishing them turns login into a way to discover
        // who has an account.
        assertThatThrownBy(() -> userService.login(new LoginRequest(unique("ghost"), "anything-at-all")))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessageContaining("Email or password is incorrect");
    }

    @Test
    void emailIsCaseInsensitive() {
        UUID merchantId = UUID.randomUUID();
        String email = unique("Case");
        userService.createUser(new CreateUserRequest(merchantId, email, "correct-horse-battery", "MERCHANT_ADMIN"));

        LoginResponse response =
                userService.login(new LoginRequest(email.toUpperCase(), "correct-horse-battery"));

        assertThat(response.merchantId()).isEqualTo(merchantId);
    }

    @Test
    void theSameEmailCannotRegisterTwice() {
        String email = unique("dup");
        userService.createUser(
                new CreateUserRequest(UUID.randomUUID(), email, "correct-horse-battery", "MERCHANT_ADMIN"));

        assertThatThrownBy(() -> userService.createUser(
                new CreateUserRequest(UUID.randomUUID(), email, "another-long-password", "MERCHANT_VIEWER")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void refusesAUserForAMerchantThatDoesNotExist() {
        UUID unknown = UUID.randomUUID();
        when(merchantServiceClient.merchantExists(unknown)).thenReturn(false);

        assertThatThrownBy(() -> userService.createUser(
                new CreateUserRequest(unknown, unique("nomerchant"), "correct-horse-battery", "MERCHANT_ADMIN")))
                .isInstanceOf(UnknownMerchantException.class);
    }

    @Test
    void loggingInRecordsWhenItHappened() {
        String email = unique("lastlogin");
        userService.createUser(
                new CreateUserRequest(UUID.randomUUID(), email, "correct-horse-battery", "MERCHANT_ADMIN"));
        assertThat(userRepository.findByEmail(email).orElseThrow().getLastLoginAt()).isNull();

        userService.login(new LoginRequest(email, "correct-horse-battery"));

        assertThat(userRepository.findByEmail(email).orElseThrow().getLastLoginAt()).isNotNull();
    }

    private String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@merchant.test";
    }
}
