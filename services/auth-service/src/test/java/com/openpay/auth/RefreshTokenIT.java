package com.openpay.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.openpay.auth.api.CreateUserRequest;
import com.openpay.auth.api.LoginRequest;
import com.openpay.auth.api.LoginResponse;
import com.openpay.auth.application.InvalidRefreshTokenException;
import com.openpay.auth.application.UserService;
import com.openpay.auth.domain.RefreshTokenRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The refresh/rotate/logout lifecycle, against a real database.
 *
 * <p>This is the class of bug a mock cannot catch: whether the unique constraint on
 * {@code token_hash} actually holds, whether {@code rotateTo} really persists, whether a second
 * {@code refresh()} call against an already-rotated token really is refused rather than quietly
 * accepted.
 */
@SpringBootTest(properties = {
        "openpay.jwt.secret=test-secret-that-is-long-enough-for-hs256",
        "openpay.jwt.refresh-ttl=P30D"
})
@Testcontainers
class RefreshTokenIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:8-alpine")).withExposedPorts(6379);

    @Autowired
    private UserService userService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @MockBean
    private MerchantServiceClient merchantServiceClient;

    @BeforeEach
    void merchantsExist() {
        when(merchantServiceClient.merchantExists(any(UUID.class))).thenReturn(true);
    }

    @Test
    void loginIssuesARefreshTokenAlongsideTheAccessToken() {
        LoginResponse login = signUpAndLogIn();

        assertThat(login.refreshToken()).isNotBlank();
        assertThat(login.refreshExpiresAt()).isNotNull();
        // The two are different credentials with different lifetimes, not the same value twice.
        assertThat(login.refreshToken()).isNotEqualTo(login.token());
    }

    @Test
    void aRefreshTokenIsStoredHashedNeverInThePlain() {
        LoginResponse login = signUpAndLogIn();

        // Exactly the same property api_keys already has: the row exists, but grepping the table
        // for the plaintext value handed to the caller finds nothing.
        assertThat(refreshTokenRepository.findAll())
                .extracting(t -> t.getTokenHash())
                .noneMatch(hash -> hash.equals(login.refreshToken()));
    }

    @Test
    void refreshingIssuesABrandNewAccessTokenAndRefreshToken() {
        LoginResponse login = signUpAndLogIn();

        LoginResponse refreshed = userService.refresh(login.refreshToken());

        assertThat(refreshed.token()).isNotEqualTo(login.token());
        assertThat(refreshed.refreshToken()).isNotEqualTo(login.refreshToken());
        // The identity carried forward is unchanged — a refresh renews the session, it does not
        // start a different one.
        assertThat(refreshed.userId()).isEqualTo(login.userId());
        assertThat(refreshed.merchantId()).isEqualTo(login.merchantId());
        assertThat(refreshed.email()).isEqualTo(login.email());
        assertThat(refreshed.role()).isEqualTo(login.role());
    }

    @Test
    void aRotatedAwayRefreshTokenCannotBeUsedAgain() {
        LoginResponse login = signUpAndLogIn();
        userService.refresh(login.refreshToken());

        // The exact token from login was already spent by the refresh above. Presenting it again
        // is not a retry of the same request — a real second use of an old credential.
        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void reusingARotatedTokenRevokesEverySessionForThatUser() {
        LoginResponse firstSession = signUpAndLogIn();
        // A second, independent login for the same account — a second device or browser tab.
        LoginResponse secondSession = userService.login(
                new LoginRequest(firstSession.email(), PASSWORD), "203.0.113.20");

        userService.refresh(firstSession.refreshToken());
        // Replaying the now-rotated-away token is the theft signature: something that should no
        // longer work being presented again, after the legitimate rotation already happened.
        assertThatThrownBy(() -> userService.refresh(firstSession.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);

        // The second session — untouched by any of the above — is revoked too. A theft response
        // that only killed the one token being replayed would leave every other session an
        // attacker might also be holding completely unaffected.
        assertThatThrownBy(() -> userService.refresh(secondSession.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void logoutRevokesTheTokenSoItCanNoLongerRefresh() {
        LoginResponse login = signUpAndLogIn();

        userService.logout(login.refreshToken());

        assertThatThrownBy(() -> userService.refresh(login.refreshToken()))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    @Test
    void loggingOutATokenThatDoesNotExistIsNotAnError() {
        // Idempotent by design: the caller's goal ("this token no longer works") is already true.
        userService.logout("00112233445566778899aabbccddeeff00112233445566778899aabbccddee");
    }

    @Test
    void logoutDoesNotDisturbAnotherSessionForTheSameUser() {
        LoginResponse firstSession = signUpAndLogIn();
        LoginResponse secondSession = userService.login(
                new LoginRequest(firstSession.email(), PASSWORD), "203.0.113.30");

        userService.logout(firstSession.refreshToken());

        // Logout ends the one session the caller named, not every session — that overreach is
        // reserved for the theft-response path above, where the ambiguity actually calls for it.
        LoginResponse renewed = userService.refresh(secondSession.refreshToken());
        assertThat(renewed.token()).isNotBlank();
    }

    @Test
    void anUnknownRefreshTokenIsRefused() {
        assertThatThrownBy(() -> userService.refresh("not-a-token-that-was-ever-issued"))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    private static final String PASSWORD = "correct-horse-battery-staple";

    private LoginResponse signUpAndLogIn() {
        UUID merchantId = UUID.randomUUID();
        String email = "refresh-" + UUID.randomUUID() + "@openpay.test";
        userService.createUser(new CreateUserRequest(merchantId, email, PASSWORD, "MERCHANT_ADMIN"));
        return userService.login(new LoginRequest(email, PASSWORD), "203.0.113.10");
    }
}
