package com.openpay.auth.application;

import com.openpay.auth.api.CreateApiKeyRequest;
import com.openpay.auth.api.CreateApiKeyResponse;
import com.openpay.auth.api.ValidateApiKeyResponse;
import com.openpay.auth.domain.ApiKey;
import com.openpay.auth.domain.ApiKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String KEY_PREFIX_PREFIX = "opk_";
    private static final int PREFIX_RANDOM_CHARS = 12;

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    @Transactional
    public CreateApiKeyResponse createApiKey(CreateApiKeyRequest request) {
        String randomKey = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        String keyPrefix = KEY_PREFIX_PREFIX + randomKey.substring(0, PREFIX_RANDOM_CHARS);
        String apiKey = keyPrefix + "." + randomKey.substring(PREFIX_RANDOM_CHARS);

        ApiKey entity = new ApiKey();
        entity.setId(UUID.randomUUID());
        entity.setMerchantId(request.merchantId());
        entity.setName(request.name());
        entity.setScope(request.scope());
        entity.setKeyPrefix(keyPrefix);
        entity.setKeyHash(hash(apiKey));
        entity.setStatus(ACTIVE_STATUS);
        entity.setExpiresAt(request.expiresAt());

        ApiKey saved = apiKeyRepository.save(entity);
        return new CreateApiKeyResponse(
                saved.getId(),
                saved.getMerchantId(),
                saved.getName(),
                saved.getScope(),
                saved.getKeyPrefix(),
                apiKey,
                saved.getStatus(),
                saved.getExpiresAt(),
                saved.getCreatedAt());
    }

    @Transactional
    public ValidateApiKeyResponse validateKey(String apiKey) {
        String keyPrefix = extractPrefix(apiKey);
        ApiKey entity = apiKeyRepository.findByKeyPrefix(keyPrefix)
                .orElseThrow(() -> new InvalidApiKeyException("API key is invalid"));

        if (!entity.getKeyHash().equals(hash(apiKey))) {
            throw new InvalidApiKeyException("API key is invalid");
        }
        if (!ACTIVE_STATUS.equals(entity.getStatus())) {
            throw new InvalidApiKeyException("API key is not active");
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidApiKeyException("API key is expired");
        }

        entity.setLastUsedAt(OffsetDateTime.now());
        return new ValidateApiKeyResponse(true, entity.getMerchantId(), entity.getScope(), entity.getStatus());
    }

    private String extractPrefix(String apiKey) {
        int separatorIndex = apiKey.indexOf('.');
        if (separatorIndex <= 0) {
            throw new InvalidApiKeyException("API key is invalid");
        }
        return apiKey.substring(0, separatorIndex);
    }

    private String hash(String rawValue) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawValue.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 should always be available", exception);
        }
    }
}
