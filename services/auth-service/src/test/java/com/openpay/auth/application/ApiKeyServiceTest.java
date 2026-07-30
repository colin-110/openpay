package com.openpay.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.openpay.auth.api.CreateApiKeyRequest;
import com.openpay.auth.api.CreateApiKeyResponse;
import com.openpay.auth.api.ValidateApiKeyResponse;
import com.openpay.auth.domain.ApiKey;
import com.openpay.auth.domain.ApiKeyRepository;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    private ApiKeyRepository apiKeyRepository;

    @InjectMocks
    private ApiKeyService apiKeyService;

    @Test
    void createsApiKeyAndReturnsPlaintextOnce() {
        UUID merchantId = UUID.randomUUID();
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));

        CreateApiKeyResponse response = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "primary", "payments:write", null));

        assertThat(response.apiKey()).startsWith("opk_");
        assertThat(response.merchantId()).isEqualTo(merchantId);
        assertThat(response.status()).isEqualTo("ACTIVE");
    }

    @Test
    void validatesStoredApiKey() {
        UUID merchantId = UUID.randomUUID();
        when(apiKeyRepository.save(any(ApiKey.class))).thenAnswer(invocation -> withTimestamps(invocation.getArgument(0)));
        CreateApiKeyResponse created = apiKeyService.createApiKey(
                new CreateApiKeyRequest(merchantId, "primary", "payments:write", null));

        ApiKey stored = new ApiKey();
        stored.setId(created.id());
        stored.setMerchantId(created.merchantId());
        stored.setName(created.name());
        stored.setScope(created.scope());
        stored.setKeyPrefix(created.keyPrefix());
        stored.setStatus(created.status());
        stored.setExpiresAt(created.expiresAt());
        stored.setKeyHash(hash(created.apiKey()));
        when(apiKeyRepository.findByKeyPrefix(created.keyPrefix())).thenReturn(Optional.of(stored));

        ValidateApiKeyResponse response = apiKeyService.validateKey(created.apiKey());

        assertThat(response.valid()).isTrue();
        assertThat(response.merchantId()).isEqualTo(merchantId);
    }

    @Test
    void rejectsMalformedApiKey() {
        assertThatThrownBy(() -> apiKeyService.validateKey("bad-key"))
                .isInstanceOf(InvalidApiKeyException.class);
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
