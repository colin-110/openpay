package com.openpay.auth.domain;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKey, UUID> {

    Optional<ApiKey> findByKeyPrefix(String keyPrefix);

    /** Targeted update so usage tracking does not drag the whole entity through dirty checking. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ApiKey k set k.lastUsedAt = :usedAt where k.id = :id")
    int touchLastUsedAt(@Param("id") UUID id, @Param("usedAt") OffsetDateTime usedAt);
}
