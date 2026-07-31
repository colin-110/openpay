package com.openpay.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.openpay.auth.api.CreateApiKeyRequest;
import com.openpay.auth.api.CreateApiKeyResponse;
import com.openpay.auth.api.ValidateApiKeyResponse;
import com.openpay.auth.application.ApiKeyService;
import com.openpay.auth.application.InvalidApiKeyException;
import com.openpay.auth.application.UnknownMerchantException;
import com.openpay.auth.domain.ApiKeyRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.time.OffsetDateTime;
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
class ApiKeyPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @MockBean
    private MerchantServiceClient merchantServiceClient;

    @BeforeEach
    void merchantsExistByDefault() {
        when(merchantServiceClient.merchantExists(any(UUID.class))).thenReturn(true);
    }

    @Test
    void storesOnlyTheHashAndValidatesThePlaintextOnce() {
        UUID merchantId = UUID.randomUUID();

        CreateApiKeyResponse created = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "primary", "payments:write", null));

        assertThat(created.apiKey()).startsWith("opk_").contains(".");
        // The plaintext must never be recoverable from storage.
        assertThat(apiKeyRepository.findByKeyPrefix(created.keyPrefix()).orElseThrow().getKeyHash())
                .isNotEqualTo(created.apiKey());

        ValidateApiKeyResponse validated = apiKeyService.validateKey(created.apiKey());
        assertThat(validated.valid()).isTrue();
        assertThat(validated.merchantId()).isEqualTo(merchantId);
    }

    @Test
    void refusesToIssueAKeyForAMerchantThatDoesNotExist() {
        UUID unknown = UUID.randomUUID();
        when(merchantServiceClient.merchantExists(unknown)).thenReturn(false);

        assertThatThrownBy(() -> apiKeyService.createApiKey(
                new CreateApiKeyRequest(unknown, "primary", "payments:write", null)))
                .isInstanceOf(UnknownMerchantException.class);
    }

    @Test
    void rejectsAKeyWithTheRightPrefixButTheWrongSecret() {
        CreateApiKeyResponse created = apiKeyService.createApiKey(
                new CreateApiKeyRequest(UUID.randomUUID(), "primary", "payments:write", null));

        assertThatThrownBy(() -> apiKeyService.validateKey(created.keyPrefix() + ".wrong-secret"))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void rejectsAnExpiredKey() {
        CreateApiKeyResponse created = apiKeyService.createApiKey(
                new CreateApiKeyRequest(UUID.randomUUID(), "short-lived", "payments:write",
                        OffsetDateTime.now().plusSeconds(1)));

        var stored = apiKeyRepository.findByKeyPrefix(created.keyPrefix()).orElseThrow();
        stored.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        apiKeyRepository.saveAndFlush(stored);

        assertThatThrownBy(() -> apiKeyService.validateKey(created.apiKey()))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void rejectsARevokedKey() {
        CreateApiKeyResponse created = apiKeyService.createApiKey(
                new CreateApiKeyRequest(UUID.randomUUID(), "primary", "payments:write", null));

        var stored = apiKeyRepository.findByKeyPrefix(created.keyPrefix()).orElseThrow();
        stored.setStatus("REVOKED");
        apiKeyRepository.saveAndFlush(stored);

        assertThatThrownBy(() -> apiKeyService.validateKey(created.apiKey()))
                .isInstanceOf(InvalidApiKeyException.class);
    }

    @Test
    void issuedKeysAreUnique() {
        UUID merchantId = UUID.randomUUID();
        CreateApiKeyResponse first = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "one", "payments:write", null));
        CreateApiKeyResponse second = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "two", "payments:write", null));

        assertThat(first.keyPrefix()).isNotEqualTo(second.keyPrefix());
        assertThat(first.apiKey()).isNotEqualTo(second.apiKey());
    }
}
