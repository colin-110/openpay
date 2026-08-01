package com.openpay.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openpay.auth.api.CreateApiKeyRequest;
import com.openpay.auth.api.CreateApiKeyResponse;
import com.openpay.auth.api.ValidateApiKeyResponse;
import com.openpay.auth.domain.ApiKey;
import com.openpay.auth.domain.ApiKeyRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @Mock
    private MerchantServiceClient merchantServiceClient;

    @Mock
    private ApiKeyUsageTracker usageTracker;

    private final ValidationAttemptLimiter attemptLimiter = mock(ValidationAttemptLimiter.class);

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        when(merchantServiceClient.merchantExists(any(UUID.class))).thenReturn(true);
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(i -> withTimestamps(i.getArgument(0)));
        // The limiter is stubbed rather than real: its counting is tested against Redis in
        // ValidationAttemptLimiterTest, and wiring that in here would make every case in this
        // class depend on infrastructure it is not about.
        apiKeyService = new ApiKeyService(
                apiKeyRepository,
                merchantServiceClient,
                usageTracker,
                attemptLimiter,
                3,
                Duration.ofMinutes(1));
    }

    @Test
    void createsApiKeyAndReturnsPlaintextOnce() {
        UUID merchantId = UUID.randomUUID();

        CreateApiKeyResponse response = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "primary", "payments:write", null));

        assertThat(response.apiKey()).startsWith("opk_");
        assertThat(response.merchantId()).isEqualTo(merchantId);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.keyPrefix()).hasSizeLessThanOrEqualTo(24);
    }

    @Test
    void refusesToIssueForAnUnknownMerchant() {
        UUID merchantId = UUID.randomUUID();
        when(merchantServiceClient.merchantExists(merchantId)).thenReturn(false);

        assertThatThrownBy(() -> apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "primary", "payments:write", null)))
                .isInstanceOf(UnknownMerchantException.class);

        verify(apiKeyRepository, never()).save(any(ApiKey.class));
    }

    @Test
    void refusesAnExpiryInThePast() {
        assertThatThrownBy(() -> apiKeyService.createApiKey(new CreateApiKeyRequest(
                UUID.randomUUID(), "primary", "payments:write", OffsetDateTime.now().minusDays(1))))
                .isInstanceOf(InvalidApiKeyRequestException.class);
    }

    @Test
    void validatesAStoredApiKey() {
        CreateApiKeyResponse created = issueKey();
        when(apiKeyRepository.findByKeyPrefix(created.keyPrefix())).thenReturn(Optional.of(storedFor(created)));
        when(usageTracker.isStale(any())).thenReturn(true);

        ValidateApiKeyResponse response = apiKeyService.validateKey(created.apiKey());

        assertThat(response.valid()).isTrue();
        assertThat(response.merchantId()).isEqualTo(created.merchantId());
    }

    @Test
    void skipsTheUsageWriteWhenTheTimestampIsStillFresh() {
        CreateApiKeyResponse created = issueKey();
        when(apiKeyRepository.findByKeyPrefix(created.keyPrefix())).thenReturn(Optional.of(storedFor(created)));
        when(usageTracker.isStale(any())).thenReturn(false);

        apiKeyService.validateKey(created.apiKey());

        // Usage tracking must not put a write on the hot path of every authenticated request.
        verify(usageTracker, never()).touch(any(UUID.class));
    }

    @Test
    void rejectsMalformedApiKey() {
        assertThatThrownBy(() -> apiKeyService.validateKey("bad-key"))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void chargesAFailedValidationAgainstThePrefixsBudget() {
        CreateApiKeyResponse created = issueKey();
        when(apiKeyRepository.findByKeyPrefix(created.keyPrefix())).thenReturn(Optional.of(storedFor(created)));

        assertThatThrownBy(() -> apiKeyService.validateKey(created.keyPrefix() + ".wrong"))
                .isInstanceOf(InvalidApiKeyException.class);

        verify(attemptLimiter).checkAllowed(created.keyPrefix(), 3);
        verify(attemptLimiter).recordFailure(created.keyPrefix(), Duration.ofMinutes(1));
    }

    @Test
    void clearsThePrefixsBudgetOnceAKeyValidates() {
        CreateApiKeyResponse created = issueKey();
        when(apiKeyRepository.findByKeyPrefix(created.keyPrefix())).thenReturn(Optional.of(storedFor(created)));

        apiKeyService.validateKey(created.apiKey());

        verify(attemptLimiter).recordSuccess(created.keyPrefix());
        verify(attemptLimiter, never()).recordFailure(anyString(), any());
    }

    @Test
    void refusesOutrightOnceTheLimiterSaysTheBudgetIsSpent() {
        // The counting itself is ValidationAttemptLimiter's job and is tested there. What matters
        // here is that the service asks first and does not swallow the answer — a throttled caller
        // must never reach the key lookup at all.
        CreateApiKeyResponse created = issueKey();
        doThrow(new TooManyAttemptsException("Too many failed authentication attempts. Try again later."))
                .when(attemptLimiter)
                .checkAllowed(anyString(), anyInt());

        assertThatThrownBy(() -> apiKeyService.validateKey(created.keyPrefix() + ".wrong"))
                .isInstanceOf(TooManyAttemptsException.class);

        verify(apiKeyRepository, never()).findByKeyPrefix(anyString());
    }

    private CreateApiKeyResponse issueKey() {
        return apiKeyService.createApiKey(
                new CreateApiKeyRequest(UUID.randomUUID(), "primary", "payments:write", null));
    }

    private ApiKey storedFor(CreateApiKeyResponse created) {
        ApiKey stored = new ApiKey();
        stored.setId(created.id());
        stored.setMerchantId(created.merchantId());
        stored.setName(created.name());
        stored.setScope(created.scope());
        stored.setKeyPrefix(created.keyPrefix());
        stored.setStatus(created.status());
        stored.setExpiresAt(created.expiresAt());
        stored.setKeyHash(hash(created.apiKey()));
        return stored;
    }

    private ApiKey withTimestamps(ApiKey apiKey) {
        try {
            var createdAt = ApiKey.class.getDeclaredField("createdAt");
            var updatedAt = ApiKey.class.getDeclaredField("updatedAt");
            createdAt.setAccessible(true);
            updatedAt.setAccessible(true);
            createdAt.set(apiKey, OffsetDateTime.now());
            updatedAt.set(apiKey, OffsetDateTime.now());
            return apiKey;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String hash(String value) {
        try {
            var method = ApiKeyService.class.getDeclaredMethod("hash", String.class);
            method.setAccessible(true);
            return (String) method.invoke(apiKeyService, value);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
