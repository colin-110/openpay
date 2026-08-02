package com.openpay.auth.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /** Every session a user currently has — used to revoke them all at once. */
    List<RefreshToken> findByUserIdAndRevokedAtIsNull(UUID userId);
}
