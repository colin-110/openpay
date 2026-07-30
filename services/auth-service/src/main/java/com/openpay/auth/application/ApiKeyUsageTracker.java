package com.openpay.auth.application;

import com.openpay.auth.domain.ApiKeyRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records API key usage without putting a write on every authenticated request.
 *
 * <p>{@code lastUsedAt} is an operational hint, not an audit record, so refreshing it at most once
 * per interval is enough — and it keeps validation a read-only query on the hot path.
 */
@Component
public class ApiKeyUsageTracker {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyUsageTracker.class);

    private final ApiKeyRepository apiKeyRepository;
    private final Duration refreshInterval;

    public ApiKeyUsageTracker(
            ApiKeyRepository apiKeyRepository,
            @Value("${openpay.auth.last-used-refresh-interval:PT5M}") Duration refreshInterval) {
        this.apiKeyRepository = apiKeyRepository;
        this.refreshInterval = refreshInterval;
    }

    public boolean isStale(OffsetDateTime lastUsedAt) {
        return lastUsedAt == null || lastUsedAt.plus(refreshInterval).isBefore(OffsetDateTime.now());
    }

    /**
     * Runs in its own transaction so a bookkeeping failure can never fail the validation that
     * triggered it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void touch(UUID apiKeyId) {
        try {
            apiKeyRepository.touchLastUsedAt(apiKeyId, OffsetDateTime.now());
        } catch (RuntimeException exception) {
            log.warn("Could not update last_used_at for API key {}", apiKeyId, exception);
        }
    }
}
