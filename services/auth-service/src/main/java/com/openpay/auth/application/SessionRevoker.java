package com.openpay.auth.application;

import com.openpay.auth.domain.RefreshToken;
import com.openpay.auth.domain.RefreshTokenRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes every active session for a user — the theft response when a refresh token that was
 * already rotated away gets presented again.
 *
 * <p>Its own bean, not a private method on {@link UserService}, for the same reason
 * {@code AuditRecorder} is its own bean: it has to run in {@code REQUIRES_NEW}, and a call from
 * inside the same class would go straight to the target method rather than through Spring's proxy,
 * so the new transaction would silently not happen. Found by testing this exact path, not by
 * inspecting the code — the in-class version looked correct and revoked nothing, because
 * {@link UserService#refresh} throws immediately afterward, and {@code @Transactional} rolls back
 * on any {@code RuntimeException}, including the one the caller is about to throw on purpose. A
 * revocation that shares its caller's transaction gets undone by the very rollback that reporting
 * the theft triggers.
 */
@Component
public class SessionRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    public SessionRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllSessions(UUID userId) {
        List<RefreshToken> active = refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId);
        active.forEach(RefreshToken::revoke);
        refreshTokenRepository.saveAll(active);
    }
}
