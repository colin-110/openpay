package com.openpay.auth.application;

import com.openpay.auth.api.CreateApiKeyRequest;
import com.openpay.auth.api.CreateApiKeyResponse;
import com.openpay.auth.api.ValidateApiKeyResponse;
import com.openpay.auth.domain.ApiKey;
import com.openpay.auth.domain.ApiKeyRepository;
import com.openpay.auth.infrastructure.MerchantServiceClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApiKeyService {

    private static final String ACTIVE_STATUS = "ACTIVE";
    private static final String KEY_PREFIX_PREFIX = "opk_";
    private static final int PREFIX_RANDOM_BYTES = 6;
    private static final int SECRET_RANDOM_BYTES = 32;

    private static final Logger log = LoggerFactory.getLogger(ApiKeyService.class);

    private final ApiKeyRepository apiKeyRepository;
    private final MerchantServiceClient merchantServiceClient;
    private final ApiKeyUsageTracker usageTracker;
    private final ValidationAttemptLimiter attemptLimiter;
    private final int maxFailedValidations;
    private final Duration failedValidationWindow;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyService(
            ApiKeyRepository apiKeyRepository,
            MerchantServiceClient merchantServiceClient,
            ApiKeyUsageTracker usageTracker,
            ValidationAttemptLimiter attemptLimiter,
            @Value("${openpay.auth.max-failed-validations:20}") int maxFailedValidations,
            @Value("${openpay.auth.failed-validation-window:PT1M}") Duration failedValidationWindow) {
        this.apiKeyRepository = apiKeyRepository;
        this.merchantServiceClient = merchantServiceClient;
        this.usageTracker = usageTracker;
        this.attemptLimiter = attemptLimiter;
        this.maxFailedValidations = maxFailedValidations;
        this.failedValidationWindow = failedValidationWindow;
    }

    @Transactional
    public CreateApiKeyResponse createApiKey(CreateApiKeyRequest request) {
        if (!merchantServiceClient.merchantExists(request.merchantId())) {
            throw new UnknownMerchantException(request.merchantId());
        }
        if (request.expiresAt() != null && request.expiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidApiKeyRequestException("expiresAt must be in the future");
        }

        String keyPrefix = KEY_PREFIX_PREFIX + randomHex(PREFIX_RANDOM_BYTES);
        String secret = randomHex(SECRET_RANDOM_BYTES);
        String apiKey = keyPrefix + "." + secret;

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
        log.info("Issued API key id={} for merchantId={}", saved.getId(), saved.getMerchantId());

        // The plaintext key is returned exactly once, here. Only its hash is persisted.
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

    @Transactional(readOnly = true)
    public ValidateApiKeyResponse validateKey(String apiKey) {
        String keyPrefix = extractPrefix(apiKey);
        attemptLimiter.checkAllowed(keyPrefix, maxFailedValidations);

        ApiKey entity = apiKeyRepository.findByKeyPrefix(keyPrefix).orElse(null);
        if (entity == null || !constantTimeEquals(entity.getKeyHash(), hash(apiKey))) {
            attemptLimiter.recordFailure(keyPrefix, failedValidationWindow);
            // Same message whether the prefix is unknown or the secret is wrong: distinguishing
            // them would tell an attacker when they have found a real prefix.
            throw new InvalidApiKeyException("API key is invalid");
        }
        if (!ACTIVE_STATUS.equals(entity.getStatus())) {
            throw new InvalidApiKeyException("API key is not active");
        }
        if (entity.getExpiresAt() != null && entity.getExpiresAt().isBefore(OffsetDateTime.now())) {
            throw new InvalidApiKeyException("API key is expired");
        }

        attemptLimiter.recordSuccess(keyPrefix);
        if (usageTracker.isStale(entity.getLastUsedAt())) {
            usageTracker.touch(entity.getId());
        }

        return new ValidateApiKeyResponse(true, entity.getMerchantId(), entity.getScope(), entity.getStatus());
    }

    private String extractPrefix(String apiKey) {
        int separatorIndex = apiKey.indexOf('.');
        if (separatorIndex <= 0) {
            throw new InvalidApiKeyException("API key is invalid");
        }
        return apiKey.substring(0, separatorIndex);
    }

    private String randomHex(int byteCount) {
        byte[] bytes = new byte[byteCount];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private boolean constantTimeEquals(String storedHash, String presentedHash) {
        return MessageDigest.isEqual(
                storedHash.getBytes(StandardCharsets.UTF_8), presentedHash.getBytes(StandardCharsets.UTF_8));
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
